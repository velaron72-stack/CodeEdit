// Port of vscode-textmate matcher.ts (scope selectors for injections) + grammar.ts nameMatcher.
#pragma once
#include <functional>
#include <string>
#include <vector>

namespace tmg {

using ScopeNames = std::vector<const std::string*>;   // root first
using NameMatcher = std::function<bool(const ScopeNames&)>;

struct MatcherWithPriority {
    NameMatcher matcher;
    int priority;   // -1 (L:), 0, 1 (R:)
};

std::vector<MatcherWithPriority> createMatchers(const std::string& selector);

}  // namespace tmg
