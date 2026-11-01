// Runs the vscode-textmate tokenization corpus (first-mate, suite1, whileTests) against the C++ engine.
// usage: run_corpus <prepared-corpus-dir>   (see prepare_corpus.py)
#include <cstdio>
#include <fstream>
#include <map>
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

struct Tok { std::string value; std::vector<std::string> scopes; };

static std::string show(const std::vector<Tok>& v) {
    std::string s;
    for (auto& t : v) {
        s += "  [" + t.value + "] ";
        for (size_t i = 0; i < t.scopes.size(); ++i) s += (i ? " > " : "") + t.scopes[i];
        s += "\n";
    }
    return s;
}

static int runSuite(const std::string& file, int& total, int& failed) {
    std::string txt, err;
    if (!readFile(file, txt)) { std::fprintf(stderr, "cannot read %s\n", file.c_str()); return 1; }
    JValue root;
    if (!parseJson(txt.data(), txt.size(), root, err)) { std::fprintf(stderr, "bad json %s: %s\n", file.c_str(), err.c_str()); return 1; }
    std::string dir = file.substr(0, file.find_last_of('/'));
    for (const JValue& t : root.arr) {
        ++total;
        const std::string* desc = t.getStr("desc");
        std::string d = desc ? *desc : "?";
        std::string scopeName;
        if (auto* s = t.getStr("grammarScopeName")) scopeName = *s;
        const std::string* gp = t.getStr("grammarPath");
        std::map<std::string, std::shared_ptr<RawGrammar>> byScope;
        std::string fail;
        for (const JValue& g : t.get("grammars")->arr) {
            std::string gtxt;
            if (!readFile(dir + "/" + g.str, gtxt)) { fail = "missing grammar " + g.str; break; }
            auto rg = parseRawGrammar(gtxt.data(), gtxt.size(), err);
            if (!rg) { fail = "parse " + g.str + ": " + err; break; }
            byScope[rg->scopeName] = rg;
            if (scopeName.empty() && gp && g.str == *gp) scopeName = rg->scopeName;
        }
        if (fail.empty() && scopeName.empty()) fail = "no grammar for test";
        std::vector<std::string> injectors;
        if (const JValue* gi = t.get("grammarInjections"))
            for (auto& s : gi->arr) injectors.push_back(s.str);
        auto reg = std::make_shared<Registry>(nullptr);
        if (fail.empty()) {
            for (auto& kv : byScope) {
                bool inj = false;
                for (auto& s : injectors) if (s == kv.first) inj = true;
                reg->addGrammar(kv.second, inj ? std::vector<std::string>{scopeName} : std::vector<std::string>{});
            }
            auto root = reg->lookup(scopeName);
            if (!root) fail = "root grammar missing";
            else {
                Grammar gr(*reg, root);
                if (!gr.prepare(err)) fail = "prepare: " + err;
                else {
                    Ref<StateStack> prev;
                    int ln = 0;
                    for (const JValue& L : t.get("lines")->arr) {
                        ++ln;
                        std::string line = L.getStr("line") ? *L.getStr("line") : "";
                        std::u16string u = toU16(line);
                        LineTokens lt(true, false);
                        TokenizeResult r = gr.tokenizeLine(u.data(), (int)u.size(), prev, false, lt, 0);
                        prev = r.state;
                        std::vector<Tok> actual, expected;
                        for (auto& tk : lt.tokens) {
                            Tok a;
                            int e = tk.end > (int)u.size() ? (int)u.size() : tk.end;   // like String.substring clamping
                            a.value = toU8(u.data() + tk.start, e > tk.start ? e - tk.start : 0);
                            a.scopes = Grammar::scopeNames(tk.scopes.get());
                            actual.push_back(a);
                        }
                        for (const JValue& e : L.get("tokens")->arr) {
                            Tok x;
                            x.value = e.getStr("value") ? *e.getStr("value") : "";
                            for (auto& s : e.get("scopes")->arr) x.scopes.push_back(s.str);
                            if (!line.empty() && x.value.empty()) continue;   // same workaround as the original runner
                            expected.push_back(x);
                        }
                        bool same = actual.size() == expected.size();
                        for (size_t i = 0; same && i < actual.size(); ++i)
                            same = actual[i].value == expected[i].value && actual[i].scopes == expected[i].scopes;
                        if (!same) {
                            fail = "line " + std::to_string(ln) + " '" + line + "'\n expected:\n" + show(expected) + " actual:\n" + show(actual);
                            break;
                        }
                    }
                }
            }
        }
        if (!fail.empty()) { ++failed; std::printf("FAIL %s / %s: %s\n", file.c_str(), d.c_str(), fail.c_str()); }
    }
    return 0;
}

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: run_corpus <dir>\n"); return 2; }
    std::string d = argv[1];
    int total = 0, failed = 0;
    for (const char* f : {"/first-mate/tests.json", "/suite1/tests.json", "/suite1/whileTests.json"})
        if (runSuite(d + f, total, failed)) return 2;
    std::printf("corpus: %d tests, %d failed\n", total, failed);
    return failed ? 1 : 0;
}
