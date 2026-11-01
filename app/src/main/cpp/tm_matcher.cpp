#include "tm_matcher.hpp"

#include <memory>

namespace tmg {

namespace {

bool isWordChar(char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'; }
bool isIdStart(char c) { return isWordChar(c) || c == '.' || c == ':'; }
bool isIdPart(char c) { return isIdStart(c) || c == '-'; }

/// regex /([LR]:|[\w\.:][\w\.:\-]*|[\,\|\-\(\)])/g
std::vector<std::string> tokenize(const std::string& in) {
    std::vector<std::string> out;
    size_t i = 0, n = in.size();
    while (i < n) {
        char c = in[i];
        if ((c == 'L' || c == 'R') && i + 1 < n && in[i + 1] == ':') { out.push_back(in.substr(i, 2)); i += 2; continue; }
        if (isIdStart(c)) {
            size_t j = i + 1;
            while (j < n && isIdPart(in[j])) ++j;
            out.push_back(in.substr(i, j - i));
            i = j;
            continue;
        }
        if (c == ',' || c == '|' || c == '-' || c == '(' || c == ')') { out.push_back(std::string(1, c)); ++i; continue; }
        ++i;
    }
    return out;
}

bool scopesAreMatching(const std::string& thisScopeName, const std::string& scopeName) {
    if (thisScopeName.empty()) return false;
    if (thisScopeName == scopeName) return true;
    size_t len = scopeName.size();
    return thisScopeName.size() > len && thisScopeName.compare(0, len, scopeName) == 0 && thisScopeName[len] == '.';
}

bool nameMatcher(const std::vector<std::string>& identifiers, const ScopeNames& scopes) {
    if (scopes.size() < identifiers.size()) return false;
    size_t lastIndex = 0;
    for (const auto& id : identifiers) {
        bool found = false;
        for (size_t i = lastIndex; i < scopes.size(); ++i) {
            if (scopesAreMatching(*scopes[i], id)) { lastIndex = i + 1; found = true; break; }
        }
        if (!found) return false;
    }
    return true;
}

bool isIdentifierToken(const std::string& t) {
    for (char c : t) if (isIdStart(c)) return true;
    return false;
}

struct P {
    std::vector<std::string> toks;
    size_t pos = 0;
    bool has = false;
    std::string token;

    void next() {
        if (pos < toks.size()) { token = toks[pos++]; has = true; } else { has = false; token.clear(); }
    }

    using M = std::shared_ptr<NameMatcher>;

    M parseOperand() {
        if (has && token == "-") {
            next();
            M e = parseOperand();
            return std::make_shared<NameMatcher>([e](const ScopeNames& in) { return e && !(*e)(in); });
        }
        if (has && token == "(") {
            next();
            M e = parseInner();
            if (has && token == ")") next();
            return e;
        }
        if (has && isIdentifierToken(token)) {
            auto ids = std::make_shared<std::vector<std::string>>();
            do { ids->push_back(token); next(); } while (has && isIdentifierToken(token));
            return std::make_shared<NameMatcher>([ids](const ScopeNames& in) { return nameMatcher(*ids, in); });
        }
        return nullptr;
    }

    M parseConjunction() {
        auto ms = std::make_shared<std::vector<M>>();
        M m = parseOperand();
        while (m) { ms->push_back(m); m = parseOperand(); }
        return std::make_shared<NameMatcher>([ms](const ScopeNames& in) {
            for (auto& m : *ms) if (!(*m)(in)) return false;
            return true;
        });
    }

    M parseInner() {
        auto ms = std::make_shared<std::vector<M>>();
        M m = parseConjunction();
        while (m) {
            ms->push_back(m);
            if (has && (token == "|" || token == ",")) {
                do { next(); } while (has && (token == "|" || token == ","));
            } else break;
            m = parseConjunction();
        }
        return std::make_shared<NameMatcher>([ms](const ScopeNames& in) {
            for (auto& m : *ms) if ((*m)(in)) return true;
            return false;
        });
    }
};

}  // namespace

std::vector<MatcherWithPriority> createMatchers(const std::string& selector) {
    std::vector<MatcherWithPriority> results;
    P p;
    p.toks = tokenize(selector);
    p.next();
    while (p.has) {
        int priority = 0;
        if (p.token.size() == 2 && p.token[1] == ':') {
            if (p.token[0] == 'R') priority = 1;
            else if (p.token[0] == 'L') priority = -1;
            p.next();
        }
        P::M m = p.parseConjunction();
        results.push_back({*m, priority});
        if (!(p.has && p.token == ",")) break;
        p.next();
    }
    return results;
}

}  // namespace tmg
