// Tokenizes every sample with its grammar through the production Session API (checkpoints, binary output) and checks
// invariants; also checks every bundled theme's colour table against the metadata limits.
// usage: smoke_all <assets/textmate> <samples-dir> [theme-id]
#include <chrono>
#include <cstdio>
#include <fstream>
#include <random>
#include <sstream>

#include "tm_onig.hpp"
#include "tm_session.hpp"

using namespace tmg;

static bool readFile(const std::string& p, std::string& out) {
    std::ifstream f(p, std::ios::binary);
    if (!f) return false;
    std::stringstream ss;
    ss << f.rdbuf();
    out = ss.str();
    return true;
}

struct Doc {
    std::u16string chars;
    std::vector<int> offsets;   // lines+1
    int lines() const { return (int)offsets.size() - 1; }
};

static Doc makeDoc(const std::string& text) {
    Doc d;
    std::u16string all = toU16(text);
    d.offsets.push_back(0);
    size_t i = 0;
    while (i <= all.size()) {
        size_t j = i;
        while (j < all.size() && all[j] != u'\n') ++j;
        size_t e = j;
        if (e > i && all[e - 1] == u'\r') --e;
        d.chars.append(all, i, e - i);
        d.offsets.push_back((int)d.chars.size());
        if (j >= all.size()) break;
        i = j + 1;
    }
    return d;
}

int main(int argc, char** argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: smoke_all <assets/textmate> <samples> [theme]\n"); return 2; }
    std::string root = argv[1], samples = argv[2], themeId = argc > 3 ? argv[3] : "dark-plus";
    std::string mtxt, err;
    if (!readFile(root + "/manifest.json", mtxt)) { std::fprintf(stderr, "no manifest\n"); return 2; }
    JValue manifest;
    if (!parseJson(mtxt.data(), mtxt.size(), manifest, err)) { std::fprintf(stderr, "manifest: %s\n", err.c_str()); return 2; }
    auto reg = std::make_shared<Registry>([&](const std::string& f, std::string& b) { return readFile(root + "/" + f, b); });
    if (!reg->loadManifest(mtxt.data(), mtxt.size(), err)) { std::fprintf(stderr, "loadManifest: %s\n", err.c_str()); return 2; }

    int bad = 0;
    // ---- all themes
    int themes = 0, maxFg = 0;
    for (const JValue& t : manifest.get("themes")->arr) {
        std::string id = *t.getStr("id"), ttxt;
        if (!readFile(root + "/themes/" + *t.getStr("file"), ttxt)) { std::printf("FAIL theme %s: unreadable\n", id.c_str()); ++bad; continue; }
        JValue tj;
        if (!parseJson(ttxt.data(), ttxt.size(), tj, err)) { std::printf("FAIL theme %s: %s\n", id.c_str(), err.c_str()); ++bad; continue; }
        std::string e2;
        auto table = Registry(nullptr).setTheme(tj, 0xFFD4D4D4u, 0xFF1E1E1Eu, e2);
        if (table.empty()) { std::printf("FAIL theme %s: %s\n", id.c_str(), e2.c_str()); ++bad; continue; }
        ++themes;
        if ((int)table.size() > maxFg) maxFg = (int)table.size();
        if (table.size() > 255) { std::printf("WARN theme %s has %zu colours (ids above 255 cannot be used as background)\n", id.c_str(), table.size()); }
        if (table.size() > 511) { std::printf("FAIL theme %s: %zu colours exceed the 9-bit foreground field\n", id.c_str(), table.size()); ++bad; }
    }
    std::printf("themes: %d loaded, max colour table %d\n", themes, maxFg);

    // ---- selected theme on the shared registry
    {
        std::string ttxt;
        JValue tj;
        std::string file;
        for (const JValue& t : manifest.get("themes")->arr) if (*t.getStr("id") == themeId) file = *t.getStr("file");
        readFile(root + "/themes/" + file, ttxt);
        parseJson(ttxt.data(), ttxt.size(), tj, err);
        if (reg->setTheme(tj, 0xFFD4D4D4u, 0xFF1E1E1Eu, err).empty()) { std::fprintf(stderr, "theme %s: %s\n", themeId.c_str(), err.c_str()); return 2; }
    }

    // ---- every language that has a sample
    int ran = 0, noSample = 0, noGrammar = 0;
    double totalMs = 0;
    size_t totalLines = 0, totalChars = 0;
    std::mt19937 rng(12345);
    for (const JValue& l : manifest.get("languages")->arr) {
        std::string id = *l.getStr("id"), scope = *l.getStr("scope");
        std::string src;
        if (!readFile(samples + "/" + id + ".sample", src)) { ++noSample; continue; }
        auto raw = reg->lookup(scope);
        if (!raw) { std::printf("FAIL %s: grammar not loadable (%s)\n", id.c_str(), reg->lastError().c_str()); ++bad; ++noGrammar; continue; }
        Doc d = makeDoc(src);
        Session s(reg, raw, 65536, 64, 4096);
        if (!s.prepare(err)) { std::printf("FAIL %s: prepare: %s\n", id.c_str(), err.c_str()); ++bad; continue; }
        std::vector<int32_t> out;
        auto t0 = std::chrono::steady_clock::now();
        if (!s.tokenize(0, d.chars.data(), (int)d.chars.size(), d.offsets.data(), d.lines(), 0, false, true, 0, out, err)) {
            std::printf("FAIL %s: tokenize: %s\n", id.c_str(), err.c_str()); ++bad; continue;
        }
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        totalMs += ms; totalLines += d.lines(); totalChars += d.chars.size();
        // invariants
        size_t p = 0;
        std::vector<size_t> lineStart(d.lines() + 1);
        bool ok = true;
        for (int ln = 0; ln < d.lines() && ok; ++ln) {
            lineStart[ln] = p;
            if (p >= out.size()) { ok = false; break; }
            int n = out[p++];
            int len = d.offsets[ln + 1] - d.offsets[ln];
            if (n < 1) { ok = false; break; }
            int prev = -1;
            for (int k = 0; k < n; ++k) {
                int st = out[p++];
                ++p;   // meta
                if (st <= prev || st > (len > 0 ? len - 1 : 0) || (k == 0 && st != 0)) { ok = false; std::printf("FAIL %s: line %d token %d start %d (len %d)\n", id.c_str(), ln, k, st, len); break; }
                prev = st;
            }
        }
        if (ok && p != out.size()) { ok = false; std::printf("FAIL %s: %zu trailing ints\n", id.c_str(), out.size() - p); }
        if (!ok) { ++bad; continue; }
        // incremental: resuming from a checkpoint must reproduce exactly the same tokens
        if (d.lines() > 200) {
            for (int trial = 0; trial < 4 && ok; ++trial) {
                int k = 1 + (int)(rng() % (d.lines() - 1));
                int cp = s.nearestCheckpoint(k);
                std::vector<int32_t> part;
                if (!s.tokenize(cp, d.chars.data(), (int)d.chars.size(), d.offsets.data() + cp, d.lines() - cp, k, false, false, 0, part, err)) {
                    std::printf("FAIL %s: resume at %d: %s\n", id.c_str(), cp, err.c_str());
                    ok = false;
                    break;
                }
                std::vector<int32_t> want(out.begin() + lineStart[k], out.end());
                if (part != want) { std::printf("FAIL %s: resume from checkpoint %d (line %d) differs from a full pass\n", id.c_str(), cp, k); ok = false; }
            }
            if (!ok) { ++bad; continue; }
        }
        ++ran;
    }
    std::printf("languages: %d tokenized, %d without sample, %d grammar failures; %.0f lines, %.1f MB chars in %.0f ms (%.0f lines/s)\n", ran, noSample, noGrammar,
                (double)totalLines, totalChars / 1048576.0, totalMs, totalLines / (totalMs / 1000.0));
    std::printf("oniguruma compile failures: %d %s\n", onigCompileFailures(), onigCompileFailures() ? onigLastError().c_str() : "");
    if (onigCompileFailures()) ++bad;
    return bad ? 1 : 0;
}
