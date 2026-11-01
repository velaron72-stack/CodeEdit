#include "tm_theme.hpp"

#include <algorithm>
#include <cstdio>
#include <cstdlib>

namespace tmg {

namespace {

bool isHex(char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'); }

bool isValidHexColor(const std::string& s) {
    if (s.empty() || s[0] != '#') return false;
    size_t n = s.size() - 1;
    if (n != 3 && n != 4 && n != 6 && n != 8) return false;
    for (size_t i = 1; i < s.size(); ++i) if (!isHex(s[i])) return false;
    return true;
}

int hexv(char c) { return c <= '9' ? c - '0' : (c | 32) - 'a' + 10; }

uint32_t toArgb(const std::string& c) {
    // c is "#RGB", "#RGBA", "#RRGGBB" or "#RRGGBBAA" (any case)
    unsigned r = 0, g = 0, b = 0, a = 255;
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

std::string argbToHex(uint32_t argb) {
    char buf[16];
    unsigned a = argb >> 24;
    if (a == 255) snprintf(buf, sizeof buf, "#%02X%02X%02X", (argb >> 16) & 255, (argb >> 8) & 255, argb & 255);
    else snprintf(buf, sizeof buf, "#%02X%02X%02X%02X", (argb >> 16) & 255, (argb >> 8) & 255, argb & 255, a);
    return buf;
}

std::vector<std::string> splitChar(const std::string& s, char sep) {
    std::vector<std::string> out;
    size_t st = 0;
    for (;;) {
        size_t i = s.find(sep, st);
        if (i == std::string::npos) { out.push_back(s.substr(st)); break; }
        out.push_back(s.substr(st, i - st));
        st = i + 1;
    }
    return out;
}

std::string trimJs(const std::string& s) {
    size_t a = 0, b = s.size();
    while (a < b && (s[a] == ' ' || s[a] == '\t' || s[a] == '\n' || s[a] == '\r' || s[a] == '\f' || s[a] == '\v')) ++a;
    while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\t' || s[b - 1] == '\n' || s[b - 1] == '\r' || s[b - 1] == '\f' || s[b - 1] == '\v')) --b;
    return s.substr(a, b - a);
}

struct ParsedRule {
    std::string scope;
    bool hasParents = false;
    std::vector<std::string> parentScopes;
    int index = 0;
    int fontStyle = FS_NotSet;
    bool hasFg = false, hasBg = false;
    std::string fg, bg;
};

int cmpStr(const std::string& a, const std::string& b) { int c = a.compare(b); return c < 0 ? -1 : (c > 0 ? 1 : 0); }

int strArrCmp(const ParsedRule& a, const ParsedRule& b) {
    if (!a.hasParents && !b.hasParents) return 0;
    if (!a.hasParents) return -1;
    if (!b.hasParents) return 1;
    size_t l1 = a.parentScopes.size(), l2 = b.parentScopes.size();
    if (l1 == l2) {
        for (size_t i = 0; i < l1; ++i) { int r = cmpStr(a.parentScopes[i], b.parentScopes[i]); if (r) return r; }
        return 0;
    }
    return static_cast<int>(l1) - static_cast<int>(l2);
}

bool strVecEq(const std::vector<std::string>& a, const std::vector<std::string>& b) { return a == b; }

int cmpBySpecificity(const TrieRule& a, const TrieRule& b) {
    if (a.scopeDepth != b.scopeDepth) return b.scopeDepth - a.scopeDepth;
    size_t ai = 0, bi = 0;
    for (;;) {
        if (ai < a.parentScopes.size() && a.parentScopes[ai] == ">") ++ai;
        if (bi < b.parentScopes.size() && b.parentScopes[bi] == ">") ++bi;
        if (ai >= a.parentScopes.size() || bi >= b.parentScopes.size()) break;
        int diff = static_cast<int>(b.parentScopes[bi].size()) - static_cast<int>(a.parentScopes[ai].size());
        if (diff != 0) return diff;
        ++ai; ++bi;
    }
    return static_cast<int>(b.parentScopes.size()) - static_cast<int>(a.parentScopes.size());
}

}  // namespace

void TrieRule::acceptOverwrite(int depth, int fs, int f, int b) {
    if (scopeDepth <= depth) scopeDepth = depth;
    if (fs != FS_NotSet) fontStyle = fs;
    if (f != 0) fg = f;
    if (b != 0) bg = b;
}

std::vector<TrieRule> TrieElement::match(const std::string& scope) const {
    if (!scope.empty()) {
        size_t dot = scope.find('.');
        std::string head = dot == std::string::npos ? scope : scope.substr(0, dot);
        std::string tail = dot == std::string::npos ? std::string() : scope.substr(dot + 1);
        auto it = children_.find(head);
        if (it != children_.end()) return it->second->match(tail);
    }
    std::vector<TrieRule> rules = withParents_;
    rules.push_back(main_);
    std::stable_sort(rules.begin(), rules.end(), [](const TrieRule& a, const TrieRule& b) { return cmpBySpecificity(a, b) < 0; });
    return rules;
}

void TrieElement::insert(int scopeDepth, const std::string& scope, const std::vector<std::string>* parentScopes, int fontStyle, int fg, int bg) {
    if (scope.empty()) { insertHere(scopeDepth, parentScopes, fontStyle, fg, bg); return; }
    size_t dot = scope.find('.');
    std::string head = dot == std::string::npos ? scope : scope.substr(0, dot);
    std::string tail = dot == std::string::npos ? std::string() : scope.substr(dot + 1);
    auto it = children_.find(head);
    TrieElement* child;
    if (it != children_.end()) child = it->second.get();
    else {
        auto c = std::make_unique<TrieElement>(main_, withParents_);
        child = c.get();
        children_.emplace(head, std::move(c));
    }
    child->insert(scopeDepth + 1, tail, parentScopes, fontStyle, fg, bg);
}

void TrieElement::insertHere(int scopeDepth, const std::vector<std::string>* parentScopes, int fontStyle, int fg, int bg) {
    if (!parentScopes) { main_.acceptOverwrite(scopeDepth, fontStyle, fg, bg); return; }
    for (auto& r : withParents_) {
        if (strVecEq(r.parentScopes, *parentScopes)) { r.acceptOverwrite(scopeDepth, fontStyle, fg, bg); return; }
    }
    if (fontStyle == FS_NotSet) fontStyle = main_.fontStyle;
    if (fg == 0) fg = main_.fg;
    if (bg == 0) bg = main_.bg;
    TrieRule r;
    r.scopeDepth = scopeDepth;
    r.parentScopes = *parentScopes;
    r.fontStyle = fontStyle; r.fg = fg; r.bg = bg;
    withParents_.push_back(std::move(r));
}

int ColorMap::getId(const std::string* c) {
    if (!c) return 0;
    std::string up = *c;
    for (auto& ch : up) if (ch >= 'a' && ch <= 'z') ch = static_cast<char>(ch - 32);
    auto it = color2id_.find(up);
    if (it != color2id_.end()) return it->second;
    int id = static_cast<int>(id2color_.size());
    color2id_.emplace(up, id);
    id2color_.push_back(up);
    return id;
}

std::shared_ptr<Theme> Theme::create(const JValue& doc, uint32_t defaultFg, uint32_t defaultBg, std::string& err) {
    if (doc.type != JValue::Obj) { err = "theme is not a JSON object"; return nullptr; }
    const JValue* settings = doc.get("tokenColors");
    if (!settings || settings->type != JValue::Arr) settings = doc.get("settings");
    std::vector<ParsedRule> rules;
    // Fallback global rule (lowest index) so a theme without its own global setting still gets sensible defaults.
    if ((defaultFg >> 24) != 0 || (defaultBg >> 24) != 0) {
        ParsedRule r;
        r.index = -1;
        if ((defaultFg >> 24) != 0) { r.hasFg = true; r.fg = argbToHex(defaultFg); }
        if ((defaultBg >> 24) != 0) { r.hasBg = true; r.bg = argbToHex(defaultBg); }
        rules.push_back(std::move(r));
    }
    if (settings && settings->type == JValue::Arr) {
        int idx = 0;
        for (const JValue& entry : settings->arr) {
            int i = idx++;
            if (entry.type != JValue::Obj) continue;
            const JValue* st = entry.get("settings");
            if (!st || st->type != JValue::Obj) continue;
            std::vector<std::string> scopes;
            const JValue* sc = entry.get("scope");
            if (sc && sc->type == JValue::Str) {
                std::string s = sc->str;
                size_t a = 0; while (a < s.size() && s[a] == ',') ++a;
                size_t b = s.size(); while (b > a && s[b - 1] == ',') --b;
                scopes = splitChar(s.substr(a, b - a), ',');
            } else if (sc && sc->type == JValue::Arr) {
                for (auto& e : sc->arr) if (e.type == JValue::Str) scopes.push_back(e.str);
            } else scopes.push_back("");
            int fontStyle = FS_NotSet;
            if (const JValue* fs = st->get("fontStyle")) {
                if (fs->type == JValue::Str) {
                    fontStyle = FS_None;
                    for (auto& seg : splitChar(fs->str, ' ')) {
                        if (seg == "italic") fontStyle |= FS_Italic;
                        else if (seg == "bold") fontStyle |= FS_Bold;
                        else if (seg == "underline") fontStyle |= FS_Underline;
                        else if (seg == "strikethrough") fontStyle |= FS_Strikethrough;
                    }
                }
            }
            const std::string* fg = st->getStr("foreground");
            if (fg && !isValidHexColor(*fg)) fg = nullptr;
            const std::string* bg = st->getStr("background");
            if (bg && !isValidHexColor(*bg)) bg = nullptr;
            for (auto& rawScope : scopes) {
                std::string s = trimJs(rawScope);
                auto segs = splitChar(s, ' ');
                ParsedRule r;
                r.scope = segs.back();
                if (segs.size() > 1) {
                    r.hasParents = true;
                    r.parentScopes.assign(segs.rbegin() + 1, segs.rend());
                }
                r.index = i;
                r.fontStyle = fontStyle;
                if (fg) { r.hasFg = true; r.fg = *fg; }
                if (bg) { r.hasBg = true; r.bg = *bg; }
                rules.push_back(std::move(r));
            }
        }
    }
    std::stable_sort(rules.begin(), rules.end(), [](const ParsedRule& a, const ParsedRule& b) {
        int r = cmpStr(a.scope, b.scope);
        if (r) return r < 0;
        r = strArrCmp(a, b);
        if (r) return r < 0;
        return a.index < b.index;
    });
    int defaultFontStyle = FS_None;
    std::string defaultForeground = "#000000", defaultBackground = "#ffffff";
    size_t first = 0;
    while (first < rules.size() && rules[first].scope.empty()) {
        const ParsedRule& r = rules[first++];
        if (r.fontStyle != FS_NotSet) defaultFontStyle = r.fontStyle;
        if (r.hasFg) defaultForeground = r.fg;
        if (r.hasBg) defaultBackground = r.bg;
    }
    auto theme = std::shared_ptr<Theme>(new Theme());
    theme->defaults_.fontStyle = defaultFontStyle;
    theme->defaults_.fg = theme->colors_.getId(&defaultForeground);
    theme->defaults_.bg = theme->colors_.getId(&defaultBackground);
    TrieRule rootRule;
    theme->root_ = std::make_unique<TrieElement>(rootRule);
    for (size_t i = first; i < rules.size(); ++i) {
        const ParsedRule& r = rules[i];
        int fg = r.hasFg ? theme->colors_.getId(&r.fg) : 0;
        int bg = r.hasBg ? theme->colors_.getId(&r.bg) : 0;
        theme->root_->insert(0, r.scope, r.hasParents ? &r.parentScopes : nullptr, r.fontStyle, fg, bg);
    }
    return theme;
}

std::vector<uint32_t> Theme::colorTable() const {
    std::vector<uint32_t> t;
    for (auto& c : colors_.colors()) t.push_back(c.empty() ? 0u : toArgb(c));
    return t;
}

bool matchesScope(const std::string& scopeName, const std::string& scopePattern) {
    if (scopePattern == scopeName) return true;
    return scopeName.size() > scopePattern.size() && scopeName.compare(0, scopePattern.size(), scopePattern) == 0 && scopeName[scopePattern.size()] == '.';
}

}  // namespace tmg
