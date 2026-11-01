// Compiles every regex (match/begin/end/while) of every grammar file with the engine's Oniguruma wrapper.
// usage: check_regex <dir-with-json-grammars>...     exit code 1 when any pattern is rejected.
#include <cstdio>
#include <dirent.h>
#include <fstream>
#include <sstream>

#include "tm_json.hpp"
#include "tm_onig.hpp"
#include "tm_util.hpp"

using namespace tmg;

static int g_total = 0, g_bad = 0, g_skipped = 0;
static std::string g_file;

static bool hasBackref(const std::u16string& s) {
    for (size_t i = 0; i + 1 < s.size(); ++i)
        if (s[i] == u'\\') {
            if (s[i + 1] >= u'1' && s[i + 1] <= u'9') return true;
            if (s[i + 1] == u'k' || s[i + 1] == u'g') return true;
            ++i;
        }
    return false;
}

static void check(const std::string& key, const std::string& value) {
    std::u16string u = toU16(value);
    ++g_total;
    if ((key == "end" || key == "while") && hasBackref(u)) { ++g_skipped; return; }   // resolved against the begin match at runtime
    RegexHolder h(u);
    if (!h.valid()) {
        ++g_bad;
        std::printf("REJECTED %s [%s] %s: %s\n", g_file.c_str(), key.c_str(), value.substr(0, 160).c_str(), onigLastError().c_str());
    }
}

static void walk(const JValue& v, const std::string& key) {
    if (v.isObj()) {
        for (auto& kv : v.obj) {
            const std::string& k = kv.first;
            if ((k == "match" || k == "begin" || k == "end" || k == "while") && kv.second.isStr()) check(k, kv.second.str);
            else walk(kv.second, k);
        }
    } else if (v.isArr()) {
        for (auto& e : v.arr) walk(e, key);
    }
}

int main(int argc, char** argv) {
    for (int a = 1; a < argc; ++a) {
        DIR* d = opendir(argv[a]);
        if (!d) { std::fprintf(stderr, "cannot open %s\n", argv[a]); return 2; }
        while (dirent* e = readdir(d)) {
            std::string name = e->d_name;
            if (name.size() < 6 || name.substr(name.size() - 5) != ".json") continue;
            std::ifstream f(std::string(argv[a]) + "/" + name, std::ios::binary);
            std::stringstream ss;
            ss << f.rdbuf();
            std::string txt = ss.str(), err;
            JValue root;
            if (!parseJson(txt.data(), txt.size(), root, err)) { std::printf("BADJSON %s: %s\n", name.c_str(), err.c_str()); ++g_bad; continue; }
            g_file = name;
            walk(root, "");
        }
        closedir(d);
    }
    std::printf("regex check: %d patterns (%d back-referenced ends skipped), %d rejected\n", g_total, g_skipped, g_bad);
    return g_bad ? 1 : 0;
}
