// Throughput benchmark of the production Session API: tokenizes a sample (repeated up to `lines` lines) per language.
// build: bash native-tests/build_host.sh <out> -O2 -g -- native-tests/bench_tokenize.cpp   (needs the Oniguruma headers, see build_host.sh)
// usage: bench_tokenize <assets/textmate> <samples-dir> <lines> <language-id>...
// Prints lines/s and chars/s for a full pass (checkpoints recorded, output requested) and for a "frontier" pass (no output).
#include <chrono>
#include <cstdio>
#include <fstream>
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
    std::vector<int> offsets;
    int lines() const { return (int)offsets.size() - 1; }
};

static Doc makeDoc(const std::string& text, int wantLines) {
    Doc d;
    std::u16string all = toU16(text);
    d.offsets.push_back(0);
    while (d.lines() < wantLines) {
        size_t i = 0;
        while (i <= all.size() && d.lines() < wantLines) {
            size_t j = i;
            while (j < all.size() && all[j] != u'\n') ++j;
            size_t e = j;
            if (e > i && all[e - 1] == u'\r') --e;
            d.chars.append(all, i, e - i);
            d.offsets.push_back((int)d.chars.size());
            if (j >= all.size()) break;
            i = j + 1;
        }
        if (all.empty()) break;
    }
    return d;
}

int main(int argc, char** argv) {
    if (argc < 5) { std::fprintf(stderr, "usage: bench_tokenize <assets/textmate> <samples> <lines> <lang>...\n"); return 2; }
    std::string root = argv[1], samples = argv[2], mtxt, err;
    int wantLines = std::atoi(argv[3]);
    if (!readFile(root + "/manifest.json", mtxt)) { std::fprintf(stderr, "no manifest\n"); return 2; }
    JValue manifest;
    if (!parseJson(mtxt.data(), mtxt.size(), manifest, err)) return 2;
    auto reg = std::make_shared<Registry>([&](const std::string& f, std::string& b) { return readFile(root + "/" + f, b); });
    if (!reg->loadManifest(mtxt.data(), mtxt.size(), err)) return 2;
    {
        std::string ttxt; JValue tj;
        std::string file;
        for (const JValue& t : manifest.get("themes")->arr) if (*t.getStr("id") == "dark-plus") file = *t.getStr("file");
        readFile(root + "/themes/" + file, ttxt);
        parseJson(ttxt.data(), ttxt.size(), tj, err);
        reg->setTheme(tj, 0xFFD4D4D4u, 0xFF1E1E1Eu, err);
    }
    for (int a = 4; a < argc; ++a) {
        std::string id = argv[a], scope, src;
        for (const JValue& l : manifest.get("languages")->arr) if (*l.getStr("id") == id) scope = *l.getStr("scope");
        if (scope.empty() || !readFile(samples + "/" + id + ".sample", src)) { std::printf("%-12s no sample/grammar\n", id.c_str()); continue; }
        auto raw = reg->lookup(scope);
        if (!raw) { std::printf("%-12s grammar not loadable\n", id.c_str()); continue; }
        Doc d = makeDoc(src, wantLines);
        double ms[2];
        for (int mode = 0; mode < 2; ++mode) {
            Session s(reg, raw, 65536, 64, 8192);
            if (!s.prepare(err)) { std::printf("%-12s prepare: %s\n", id.c_str(), err.c_str()); break; }
            std::vector<int32_t> out;
            auto t0 = std::chrono::steady_clock::now();
            s.tokenize(0, d.chars.data(), (int)d.chars.size(), d.offsets.data(), d.lines(), mode == 0 ? 0 : INT32_MAX, false, true, 0, out, err);
            ms[mode] = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        }
        std::printf("%-12s %6d lines %8zu chars | full %7.0f ms = %6.0f lines/s %5.1f Mchar/s | frontier %7.0f ms = %6.0f lines/s\n", id.c_str(), d.lines(),
                    d.chars.size(), ms[0], d.lines() / ms[0] * 1000.0, d.chars.size() / ms[0] / 1000.0, ms[1], d.lines() / ms[1] * 1000.0);
    }
    return 0;
}
