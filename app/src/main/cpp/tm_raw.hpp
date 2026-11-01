// Immutable raw (parsed) TextMate grammar as found in the JSON files. Shared between sessions.
#pragma once
#include <deque>
#include <memory>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "tm_json.hpp"

namespace tmg {

struct RawRule;

struct RawCapture {
    int index;
    RawRule* rule;
};

struct RawRepo {
    std::vector<std::pair<std::string, RawRule*>> items;   // document order
    std::unordered_map<std::string, RawRule*> index;
    void set(const std::string& k, RawRule* r);
};

/// Mirrors IRawRule. Strings that vscode-textmate tests for truthiness are plain strings here (empty == falsy).
struct RawRule {
    std::string include;
    std::string name;
    std::string contentName;
    std::u16string match;
    bool hasBegin = false;
    std::u16string begin;
    std::u16string end;
    std::u16string whileRe;
    bool hasCaptures = false, hasBeginCaptures = false, hasEndCaptures = false, hasWhileCaptures = false;
    std::vector<RawCapture> captures, beginCaptures, endCaptures, whileCaptures;
    bool hasPatterns = false;
    std::vector<RawRule*> patterns;
    std::shared_ptr<RawRepo> repository;   // null when the rule has none
    bool applyEndPatternLast = false;
};

struct RawGrammar {
    std::string scopeName;
    std::string name;
    std::string injectionSelector;
    std::deque<RawRule> arena;                          // owns every RawRule below
    RawRule root;                                       // {patterns, repository}: used when a grammar is itself an injection rule
    std::shared_ptr<RawRepo> repository;                // never null
    std::vector<std::pair<std::string, RawRule*>> injections;
};

/// Returns nullptr and sets `err` on malformed input or a missing scopeName.
std::shared_ptr<RawGrammar> parseRawGrammar(const char* data, size_t n, std::string& err);

}  // namespace tmg
