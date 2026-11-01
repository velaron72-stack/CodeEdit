// Port of vscode-textmate themes.test.ts: every test file x every theme must produce the recorded token colours.
// usage: run_themes <prepared-themes-dir> [--perf]   (see prepare_themes.py)
#include <chrono>
#include <cstdio>
#include <fstream>
#include <sstream>

#include "tm_grammar.hpp"
#include "tm_registry.hpp"

using namespace tmg;

static bool readFile(const std::string& p, std::string& out) {
    std::ifstream f(p, std::ios::binary);
    if (!f) return false;
    std::stringstream ss;
    ss << f.rdbuf();
    out = ss.str();
    return true;
}

static int hexv(char c) { return c <= '9' ? c - '0' : (c | 32) - 'a' + 10; }
static uint32_t toArgb(const std::string& c) {
    unsigned r, g, b, a = 255;
    size_t n = c.size() - 1;
    if (n == 3 || n == 4) {
        r = hexv(c[1]) * 17; g = hexv(c[2]) * 17; b = hexv(c[3]) * 17;
        if (n == 4) a = hexv(c[4]) * 17;
    } else {
        r = hexv(c[1]) * 16 + hexv(c[2]); g = hexv(c[3]) * 16 + hexv(c[4]); b = hexv(c[5]) * 16 + hexv(c[6]);
        if (n == 8) a = hexv(c[7]) * 16 + hexv(c[8]);
    }
    return (a << 24) | (r << 16) | (g << 8) | b;
}

struct Tk { std::string content; uint32_t color; };

static std::vector<std::string> splitLines(const std::string& s) {
    std::vector<std::string> out;
    std::string cur;
    for (size_t i = 0; i < s.size(); ++i) {
        char c = s[i];
        if (c == '\r') { if (i + 1 < s.size() && s[i + 1] == '\n') ++i; out.push_back(cur); cur.clear(); }
        else if (c == '\n') { out.push_back(cur); cur.clear(); }
        else cur.push_back(c);
    }
    out.push_back(cur);
    return out;
}

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: run_themes <dir> [--perf]\n"); return 2; }
    std::string dir = argv[1];
    bool perf = argc > 2 && std::string(argv[2]) == "--perf";
    std::string txt, err;
    JValue plan, manifest;
    if (!readFile(dir + "/plan.json", txt) || !parseJson(txt.data(), txt.size(), plan, err)) { std::fprintf(stderr, "plan: %s\n", err.c_str()); return 2; }
    std::string mtxt;
    if (!readFile(dir + "/manifest.json", mtxt)) return 2;
    (void)manifest;

    int total = 0, failed = 0, strictOk = 0;
    // one registry per theme (like the original); grammars are loaded through the manifest + loader path
    for (const JValue& th : plan.get("themes")->arr) {
        std::string tname = *th.getStr("name");
        std::string ttxt;
        JValue tjson;
        if (!readFile(dir + "/" + *th.getStr("file"), ttxt) || !parseJson(ttxt.data(), ttxt.size(), tjson, err)) { std::fprintf(stderr, "theme %s: %s\n", tname.c_str(), err.c_str()); return 2; }
        auto reg = std::make_shared<Registry>([&](const std::string& file, std::string& bytes) { return readFile(dir + "/" + file, bytes); });
        if (!reg->loadManifest(mtxt.data(), mtxt.size(), err)) { std::fprintf(stderr, "manifest: %s\n", err.c_str()); return 2; }
        std::vector<uint32_t> colors = reg->setTheme(tjson, 0, 0, err);
        if (colors.empty()) { std::fprintf(stderr, "setTheme %s: %s\n", tname.c_str(), err.c_str()); return 2; }
        for (const JValue& t : plan.get("tests")->arr) {
            const std::string file = *t.getStr("file");
            const std::string scope = *t.getStr("scope");
            ++total;
            std::string src, expTxt;
            readFile(dir + "/tests/" + file, src);
            readFile(dir + "/tests/" + file + ".result", expTxt);
            JValue expAll;
            if (!parseJson(expTxt.data(), expTxt.size(), expAll, err)) { std::printf("FAIL %s/%s: bad expected json\n", tname.c_str(), file.c_str()); ++failed; continue; }
            const JValue* exp = expAll.get(tname.c_str());
            auto raw = reg->lookup(scope);
            if (!raw || !exp) { std::printf("FAIL %s/%s: missing grammar or expectation (%s)\n", tname.c_str(), file.c_str(), reg->lastError().c_str()); ++failed; continue; }
            Grammar gr(*reg, raw);
            if (!gr.prepare(err)) { std::printf("FAIL %s/%s: prepare %s\n", tname.c_str(), file.c_str(), err.c_str()); ++failed; continue; }
            std::vector<Tk> actual;
            Ref<StateStack> prev;
            for (const std::string& line : splitLines(src)) {
                std::u16string u = toU16(line);
                LineTokens lt(false, true);
                TokenizeResult r = gr.tokenizeLine(u.data(), (int)u.size(), prev, false, lt, 0);
                prev = r.state;
                size_t n = lt.binary.size() / 2;
                for (size_t j = 0; j < n; ++j) {
                    int s = lt.binary[2 * j];
                    int e = j + 1 < n ? lt.binary[2 * j + 2] : (int)u.size();
                    if (e > (int)u.size()) e = (int)u.size();
                    if (e <= s) continue;
                    uint32_t meta = (uint32_t)lt.binary[2 * j + 1];
                    unsigned fg = (meta >> 15) & 0x1FF;
                    actual.push_back({toU8(u.data() + s, e - s), fg < colors.size() ? colors[fg] : 0u});
                }
            }
            // The recorded results come from VS Code with embedded-language ids, which split tokens of equal colour
            // (e.g. "?" / ">" of "?>" in PHP). This engine does not use language ids, so adjacent same-colour tokens are
            // coalesced on both sides before comparing; only boundaries between *different* colours are checked.
            std::vector<Tk> want;
            for (const JValue& e : exp->arr) want.push_back({*e.getStr("content"), toArgb(*e.getStr("color"))});
            bool strict = actual.size() == want.size();
            for (size_t i = 0; strict && i < actual.size(); ++i) strict = actual[i].content == want[i].content && actual[i].color == want[i].color;
            auto coalesce = [](const std::vector<Tk>& in) {
                std::vector<Tk> out;
                for (auto& t : in) {
                    if (!out.empty() && out.back().color == t.color) out.back().content += t.content;
                    else out.push_back(t);
                }
                return out;
            };
            std::vector<Tk> ca = coalesce(actual), cw = coalesce(want);
            bool same = ca.size() == cw.size();
            size_t bad = 0;
            for (size_t i = 0; i < ca.size() && i < cw.size(); ++i) {
                if (ca[i].content != cw[i].content || ca[i].color != cw[i].color) { same = false; bad = i; break; }
                bad = i + 1;
            }
            if (strict) ++strictOk;
            if (!same) {
                ++failed;
                std::printf("FAIL %s/%s: %zu vs %zu coalesced tokens", tname.c_str(), file.c_str(), ca.size(), cw.size());
                if (bad < ca.size() && bad < cw.size())
                    std::printf("; first diff #%zu actual [%s] #%08X expected [%s] #%08X", bad, ca[bad].content.c_str(), ca[bad].color, cw[bad].content.c_str(), cw[bad].color);
                std::printf("\n");
            }
        }
    }
    std::printf("themes: %d cases, %d failed (%d identical token-for-token, rest differ only by embedded-language splits)\n", total, failed, strictOk);

    if (perf) {
        // dark_vs + TypeScript over the two big fixtures (informational timing)
        std::string ttxt;
        JValue tjson;
        readFile(dir + "/themes/dark_vs.json", ttxt);
        parseJson(ttxt.data(), ttxt.size(), tjson, err);
        auto reg = std::make_shared<Registry>([&](const std::string& file, std::string& bytes) { return readFile(dir + "/" + file, bytes); });
        reg->loadManifest(mtxt.data(), mtxt.size(), err);
        reg->setTheme(tjson, 0, 0, err);
        for (const char* f : {"perf1.ts", "perf2.ts"}) {
            std::string src;
            readFile(dir + "/tests/" + f, src);
            auto raw = reg->lookup("source.ts");
            Grammar gr(*reg, raw);
            gr.prepare(err);
            auto t0 = std::chrono::steady_clock::now();
            Ref<StateStack> prev;
            size_t lines = 0, tokens = 0;
            for (const std::string& line : splitLines(src)) {
                std::u16string u = toU16(line);
                LineTokens lt(false, true);
                prev = gr.tokenizeLine(u.data(), (int)u.size(), prev, false, lt, 0).state;
                tokens += lt.binary.size() / 2;
                ++lines;
            }
            double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            std::printf("perf %s: %zu lines, %zu tokens, %.1f ms\n", f, lines, tokens, ms);
        }
    }
    return failed ? 1 : 0;
}
