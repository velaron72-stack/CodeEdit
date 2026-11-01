// Port of vscode-textmate theme.ts: theme parsing, trie, colour map. (Font family/size/line-height are ignored.)
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "tm_json.hpp"

namespace tmg {

enum FontStyle : int { FS_NotSet = -1, FS_None = 0, FS_Italic = 1, FS_Bold = 2, FS_Underline = 4, FS_Strikethrough = 8 };

struct Style {
    int fontStyle = FS_NotSet;
    int fg = 0;
    int bg = 0;
};

struct TrieRule {
    int scopeDepth = 0;
    std::vector<std::string> parentScopes;   // deepest parent first (index 0), may contain ">"
    int fontStyle = FS_NotSet;
    int fg = 0;
    int bg = 0;
    void acceptOverwrite(int scopeDepth, int fontStyle, int fg, int bg);
};

class TrieElement {
public:
    explicit TrieElement(TrieRule mainRule) : main_(std::move(mainRule)) {}
    TrieElement(TrieRule mainRule, std::vector<TrieRule> withParents) : main_(std::move(mainRule)), withParents_(std::move(withParents)) {}
    std::vector<TrieRule> match(const std::string& scope) const;
    void insert(int scopeDepth, const std::string& scope, const std::vector<std::string>* parentScopes, int fontStyle, int fg, int bg);

private:
    void insertHere(int scopeDepth, const std::vector<std::string>* parentScopes, int fontStyle, int fg, int bg);
    TrieRule main_;
    std::vector<TrieRule> withParents_;
    std::unordered_map<std::string, std::unique_ptr<TrieElement>> children_;
};

class ColorMap {
public:
    int getId(const std::string* colorOrNull);   // upper-cases the colour; 0 for null
    const std::vector<std::string>& colors() const { return id2color_; }   // index = id (0 unused)
private:
    std::unordered_map<std::string, int> color2id_;
    std::vector<std::string> id2color_{std::string()};
};

class Theme {
public:
    /// `themeJson` is a VS Code / tm-themes style document ("tokenColors" or "settings").
    /// `defaultFg/defaultBg` (ARGB, alpha 0 = unspecified) are used only when the theme has no global setting of its own.
    static std::shared_ptr<Theme> create(const JValue& themeJson, uint32_t defaultFg, uint32_t defaultBg, std::string& err);

    const Style& defaults() const { return defaults_; }
    /// Sorted candidate rules for a scope name (cached by the caller per scope).
    std::vector<TrieRule> matchRoot(const std::string& scopeName) const { return root_->match(scopeName); }
    /// ARGB per colour id (index 0 = transparent/unused).
    std::vector<uint32_t> colorTable() const;

private:
    ColorMap colors_;
    Style defaults_;
    std::unique_ptr<TrieElement> root_;
};

/// scopeName (leaf) + chain of parent scope names (nearest first) -> does `parentScopes` match? (theme.ts _scopePathMatchesParentScopes)
template <class Node>
bool scopePathMatchesParentScopes(const Node* parentPath, const std::vector<std::string>& parentScopes);

bool matchesScope(const std::string& scopeName, const std::string& scopePattern);

}  // namespace tmg
