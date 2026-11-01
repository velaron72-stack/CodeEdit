#include "tm_registry.hpp"

namespace tmg {

Registry::Registry(Loader loader) : loader_(std::move(loader)) {
    JValue empty;
    empty.type = JValue::Obj;
    std::string err;
    theme_.theme = Theme::create(empty, 0xFF000000u, 0xFFFFFFFFu, err);
    theme_.epoch = 1;
}

bool Registry::loadManifest(const char* json, size_t n, std::string& err) {
    JValue doc;
    if (!parseJson(json, n, doc, err)) return false;
    const JValue* langs = doc.get("languages");
    if (!langs || langs->type != JValue::Arr) { err = "manifest has no languages"; return false; }
    std::lock_guard<std::mutex> lk(mu_);
    for (const JValue& l : langs->arr) {
        if (l.type != JValue::Obj) continue;
        const std::string* scope = l.getStr("scope");
        const std::string* file = l.getStr("file");
        if (!scope || !file) continue;
        scopeToFile_[*scope] = "grammars/" + *file;
        if (const JValue* inj = l.get("injectTo")) {
            if (inj->type == JValue::Arr)
                for (const JValue& t : inj->arr)
                    if (t.type == JValue::Str) injections_[t.str].push_back(*scope);
        }
    }
    return true;
}

std::shared_ptr<const RawGrammar> Registry::lookup(const std::string& scopeName) {
    std::lock_guard<std::mutex> lk(mu_);
    auto it = grammars_.find(scopeName);
    if (it != grammars_.end()) return it->second;
    if (failed_.count(scopeName)) return nullptr;
    auto f = scopeToFile_.find(scopeName);
    if (f == scopeToFile_.end() || !loader_) { failed_[scopeName] = true; return nullptr; }
    std::string bytes;
    if (!loader_(f->second, bytes)) { failed_[scopeName] = true; lastError_ = "cannot load " + f->second; return nullptr; }
    std::string err;
    std::shared_ptr<RawGrammar> g = parseRawGrammar(bytes.data(), bytes.size(), err);
    if (!g) { failed_[scopeName] = true; lastError_ = f->second + ": " + err; return nullptr; }
    grammars_[scopeName] = g;
    return g;
}

std::vector<std::string> Registry::injectionsFor(const std::string& scopeName) const {
    std::lock_guard<std::mutex> lk(mu_);
    auto it = injections_.find(scopeName);
    return it == injections_.end() ? std::vector<std::string>() : it->second;
}

void Registry::addGrammar(std::shared_ptr<const RawGrammar> g, std::vector<std::string> injectsInto) {
    std::lock_guard<std::mutex> lk(mu_);
    grammars_[g->scopeName] = g;
    failed_.erase(g->scopeName);
    for (auto& t : injectsInto) injections_[t].push_back(g->scopeName);
}

Registry::ThemeSnapshot Registry::themeSnapshot() const {
    std::lock_guard<std::mutex> lk(themeMu_);
    return theme_;
}

std::vector<uint32_t> Registry::setTheme(const JValue& themeJson, uint32_t defaultFg, uint32_t defaultBg, std::string& err) {
    std::shared_ptr<Theme> t = Theme::create(themeJson, defaultFg, defaultBg, err);
    if (!t) return {};
    std::vector<uint32_t> table = t->colorTable();
    std::lock_guard<std::mutex> lk(themeMu_);
    theme_.theme = t;
    ++theme_.epoch;
    return table;
}

std::string Registry::lastError() const {
    std::lock_guard<std::mutex> lk(mu_);
    return lastError_;
}

}  // namespace tmg
