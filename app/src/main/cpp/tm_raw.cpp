#include "tm_raw.hpp"

#include <cstdlib>

#include "tm_util.hpp"

namespace tmg {

void RawRepo::set(const std::string& k, RawRule* r) {
    auto it = index.find(k);
    if (it == index.end()) {
        items.emplace_back(k, r);
        index.emplace(k, r);
    } else {
        it->second = r;
        for (auto& p : items) if (p.first == k) { p.second = r; break; }
    }
}

namespace {

struct Builder {
    RawGrammar& g;

    RawRule* newRule() { g.arena.emplace_back(); return &g.arena.back(); }

    static bool truthyStr(const JValue* v) { return v && v->type == JValue::Str && !v->str.empty(); }

    void captures(const JValue* v, bool& has, std::vector<RawCapture>& out) {
        if (!v || v->type != JValue::Obj) return;
        has = true;
        for (auto& kv : v->obj) {
            const std::string& k = kv.first;
            if (k.empty() || k[0] < '0' || k[0] > '9') continue;   // parseInt semantics: only leading digits count
            int idx = static_cast<int>(strtol(k.c_str(), nullptr, 10));
            RawRule* r = convert(kv.second);
            bool replaced = false;
            for (auto& c : out) if (c.index == idx) { c.rule = r; replaced = true; break; }
            if (!replaced) out.push_back({idx, r});
        }
    }

    std::shared_ptr<RawRepo> repo(const JValue* v) {
        if (!v || v->type != JValue::Obj) return nullptr;
        auto r = std::make_shared<RawRepo>();
        for (auto& kv : v->obj) r->set(kv.first, convert(kv.second));
        return r;
    }

    RawRule* convert(const JValue& v) {
        RawRule* r = newRule();
        if (v.type != JValue::Obj) return r;
        if (auto s = v.getStr("include")) r->include = *s;
        if (auto s = v.getStr("name")) r->name = *s;
        if (auto s = v.getStr("contentName")) r->contentName = *s;
        if (auto s = v.getStr("match")) r->match = toU16(*s);
        if (const JValue* b = v.get("begin")) {
            if (b->type == JValue::Str) { r->hasBegin = true; r->begin = toU16(b->str); }
        }
        if (auto s = v.getStr("end")) r->end = toU16(*s);
        if (auto s = v.getStr("while")) r->whileRe = toU16(*s);
        captures(v.get("captures"), r->hasCaptures, r->captures);
        captures(v.get("beginCaptures"), r->hasBeginCaptures, r->beginCaptures);
        captures(v.get("endCaptures"), r->hasEndCaptures, r->endCaptures);
        captures(v.get("whileCaptures"), r->hasWhileCaptures, r->whileCaptures);
        if (const JValue* p = v.get("patterns")) {
            if (p->type == JValue::Arr) {
                r->hasPatterns = true;
                r->patterns.reserve(p->arr.size());
                for (auto& e : p->arr) r->patterns.push_back(convert(e));
            }
        }
        r->repository = repo(v.get("repository"));
        if (const JValue* a = v.get("applyEndPatternLast")) {
            r->applyEndPatternLast = (a->type == JValue::Bool && a->b) || (a->type == JValue::Num && a->num != 0);
        }
        return r;
    }
};

}  // namespace

std::shared_ptr<RawGrammar> parseRawGrammar(const char* data, size_t n, std::string& err) {
    JValue doc;
    if (!parseJson(data, n, doc, err)) return nullptr;
    if (doc.type != JValue::Obj) { err = "grammar is not a JSON object"; return nullptr; }
    auto g = std::make_shared<RawGrammar>();
    const std::string* scope = doc.getStr("scopeName");
    if (!scope) { err = "grammar has no scopeName"; return nullptr; }
    g->scopeName = *scope;
    if (auto s = doc.getStr("name")) g->name = *s;
    if (auto s = doc.getStr("injectionSelector")) g->injectionSelector = *s;
    Builder b{*g};
    if (const JValue* p = doc.get("patterns")) {
        if (p->type == JValue::Arr) {
            g->root.hasPatterns = true;
            for (auto& e : p->arr) g->root.patterns.push_back(b.convert(e));
        }
    }
    g->repository = b.repo(doc.get("repository"));
    if (!g->repository) g->repository = std::make_shared<RawRepo>();
    g->root.repository = g->repository;
    if (const JValue* inj = doc.get("injections")) {
        if (inj->type == JValue::Obj) {
            for (auto& kv : inj->obj) {
                RawRule* r = b.convert(kv.second);
                bool replaced = false;
                for (auto& e : g->injections) if (e.first == kv.first) { e.second = r; replaced = true; break; }
                if (!replaced) g->injections.emplace_back(kv.first, r);
            }
        }
    }
    return g;
}

}  // namespace tmg
