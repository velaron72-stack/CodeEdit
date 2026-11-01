// Port of vscode-textmate rule.ts + grammar.ts + tokenizeString.ts (binary token output only, no fonts / embedded languages).
#pragma once
#include <chrono>
#include <cstdint>
#include <deque>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "tm_matcher.hpp"
#include "tm_onig.hpp"
#include "tm_raw.hpp"
#include "tm_registry.hpp"
#include "tm_theme.hpp"
#include "tm_util.hpp"

namespace tmg {

constexpr int kEndRuleId = -1;
constexpr int kWhileRuleId = -2;

// ---- EncodedTokenAttributes (vscode-textmate encodedTokenAttributes.ts) ----
namespace attr {
constexpr int kTokenTypeNotSet = 8;
uint32_t set(uint32_t existing, int tokenType, int fontStyle, int fg, int bg);
inline int foreground(uint32_t a) { return static_cast<int>((a & 0x00FF8000u) >> 15); }
inline int background(uint32_t a) { return static_cast<int>((a & 0xFF000000u) >> 24); }
inline int fontStyle(uint32_t a) { return static_cast<int>((a & 0x00007800u) >> 11); }
inline int tokenType(uint32_t a) { return static_cast<int>((a & 0x00000300u) >> 8); }
}  // namespace attr

struct ScopeInfo {
    std::string name;
    int tokenType = attr::kTokenTypeNotSet;   // OptionalStandardTokenType
    uint32_t themeEpoch = 0xFFFFFFFFu;
    std::vector<TrieRule> rules;               // theme candidates, valid while themeEpoch matches
};

/// Linked list of scope names with the token attributes accumulated up to this node.
struct AttrStack : RcNode {
    Ref<AttrStack> parent;
    const ScopeInfo* scope = nullptr;
    uint32_t attrs = 0;
};

struct StateStack : RcNode {
    Ref<StateStack> parent;
    int ruleId = 0;
    int enterPos = -1;
    int anchorPos = -1;
    bool beginRuleCapturedEOL = false;
    std::shared_ptr<const std::u16string> endRule;
    Ref<AttrStack> nameScopes;
    Ref<AttrStack> contentScopes;
    int depth = 1;
};

enum class RuleKind : uint8_t { Capture, Match, IncludeOnly, BeginEnd, BeginWhile };

class Grammar;

class RegExpSource {
public:
    RegExpSource(const std::u16string& regExpSource, int ruleId);
    void setSource(const std::u16string& s);
    const std::u16string& resolveAnchors(bool allowA, bool allowG) const;
    std::u16string resolveBackReferences(const char16_t* line, const std::vector<Cap>& caps) const;

    std::u16string source;
    int ruleId;
    bool hasAnchor = false;
    bool hasBackReferences = false;

private:
    void buildAnchorCache();
    std::u16string cache_[4];   // A0_G0, A0_G1, A1_G0, A1_G1
};

class RegExpSourceList {
public:
    void push(RegExpSource* s) { items_.push_back(s); hasAnchors_ = hasAnchors_ || s->hasAnchor; }
    void pushOwned(std::unique_ptr<RegExpSource> s) { RegExpSource* p = s.get(); owned_.push_back(std::move(s)); push(p); }
    void unshiftOwned(std::unique_ptr<RegExpSource> s);
    void unshift(RegExpSource* s);
    size_t length() const { return items_.size(); }
    void setSource(size_t index, const std::u16string& newSource);
    std::shared_ptr<Scanner> compile(RegexPool& pool);
    std::shared_ptr<Scanner> compileAG(RegexPool& pool, bool allowA, bool allowG);

private:
    std::shared_ptr<Scanner> build(RegexPool& pool, bool resolve, bool allowA, bool allowG);
    void disposeCaches();
    std::vector<RegExpSource*> items_;
    std::vector<std::unique_ptr<RegExpSource>> owned_;
    bool hasAnchors_ = false;
    std::shared_ptr<Scanner> cached_;
    std::shared_ptr<Scanner> anchorCache_[4];
};

struct Rule {
    RuleKind kind = RuleKind::Match;
    int id = 0;
    // "name" / "contentName"
    bool hasName = false, nameCapturing = false;
    std::string nameRaw;
    std::vector<const ScopeInfo*> nameScopes;      // pre-split when not capturing
    bool hasContentName = false, contentNameCapturing = false;
    std::string contentRaw;
    std::vector<const ScopeInfo*> contentScopes;

    int retokenizeId = 0;                          // Capture
    std::unique_ptr<RegExpSource> begin;           // Match: the match regex; BeginEnd/BeginWhile: begin regex
    std::unique_ptr<RegExpSource> endOrWhile;      // BeginEnd: end; BeginWhile: while
    std::vector<int> captures;                     // Match.captures / begin captures (0 == none)
    std::vector<int> endCaptures;                  // BeginEnd.endCaptures / BeginWhile.whileCaptures
    bool endHasBackReferences = false;             // also "whileHasBackReferences"
    bool applyEndPatternLast = false;
    bool hasMissingPatterns = false;
    std::vector<int> patterns;
    std::unique_ptr<RegExpSourceList> list;        // compiled pattern list (Match/IncludeOnly/BeginEnd/BeginWhile)
    std::unique_ptr<RegExpSourceList> whileList;   // BeginWhile only
};

using Repo = std::unordered_map<std::string, const RawRule*>;
using RepoPtr = std::shared_ptr<const Repo>;

/// Collects tokens of one line.
class LineTokens {
public:
    LineTokens(bool emitScopes, bool mergeEqualMetadata) : emitScopes_(emitScopes), merge_(mergeEqualMetadata) {}
    void produce(const AttrStack* scopes, int endIndex);
    /// Finishes the line; binary: [start, meta]* in `binary`; scope mode: `tokens`.
    void finish(const AttrStack* contentScopes, int lineLength);

    struct Tok {
        int start, end;
        Ref<AttrStack> scopes;
    };
    std::vector<int32_t> binary;
    std::vector<Tok> tokens;

private:
    bool emitScopes_;
    bool merge_;
    int lastEnd_ = 0;
};

struct TokenizeResult {
    Ref<StateStack> state;
    bool stoppedEarly = false;
};

class Grammar {
public:
    Grammar(Registry& registry, std::shared_ptr<const RawGrammar> root);
    ~Grammar();

    /// Compiles the rule graph (loads dependency grammars). False + `err` on failure.
    bool prepare(std::string& err);
    /// Binds the theme used for subsequent tokenization.
    void useTheme(const Registry::ThemeSnapshot& snap);
    uint32_t themeEpoch() const { return themeEpoch_; }

    /// State before the first line of a document.
    Ref<StateStack> initialState();
    /// Tokenizes one line (without EOL). `prev` null => first line. Appends to `out`.
    TokenizeResult tokenizeLine(const char16_t* text, int len, const Ref<StateStack>& prev, bool forceNotFirstLine, LineTokens& out, int timeLimitMs);

    uint32_t defaultMetadata() const { return defaultMeta_; }
    const std::string& rootScopeName() const { return root_->scopeName; }

    // --- used by rules / tokenizer ---
    Rule* getRule(int id) const { return id > 0 && id < static_cast<int>(rules_.size()) ? rules_[id].get() : nullptr; }
    RegexPool& pool() { return pool_; }
    const ScopeInfo* intern(const std::string& name);
    Ref<AttrStack> pushAttributed(const Ref<AttrStack>& base, const std::string& scopePath);
    Ref<AttrStack> pushScopes(const Ref<AttrStack>& base, const std::vector<const ScopeInfo*>& scopes);
    Ref<AttrStack> pushName(const Ref<AttrStack>& base, const Rule& r, const char16_t* line, const std::vector<Cap>& caps);
    Ref<AttrStack> pushContentName(const Ref<AttrStack>& base, const Rule& r, const char16_t* line, const std::vector<Cap>& caps);
    /// Debug/test helper: names root→leaf.
    static std::vector<std::string> scopeNames(const AttrStack* s);

private:
    struct Instance {
        std::shared_ptr<const RawGrammar> raw;
        RepoPtr repo;
        RawRule self;   // synthetic $self rule
    };
    struct Injection {
        NameMatcher matcher;
        int priority;
        int ruleId;
    };

    // rule factory
    int getCompiledRuleId(const RawRule* desc, RepoPtr repository);
    int createCaptureRule(const RawRule* desc, int retokenizeId);
    std::vector<int> compileCaptures(const std::vector<RawCapture>& caps, RepoPtr repository);
    struct PatternsResult { std::vector<int> ids; bool hasMissing; };
    PatternsResult compilePatterns(const std::vector<RawRule*>* patterns, const RepoPtr& repository);
    void initNames(Rule& r, const std::string& name, const std::string& contentName);
    Instance* initGrammar(const std::shared_ptr<const RawGrammar>& raw, const RawRule* base);
    Instance* getExternalGrammar(const std::string& scopeName, const RepoPtr& repository);
    void collectInjections();
    void collectInjection(const std::string& selector, const RawRule* rule, Instance* inst);
    int registerRuleSlot();

    // pattern collection / compilation
    void collectPatterns(const Rule& r, RegExpSourceList& out, int depth);
    std::shared_ptr<Scanner> compileRule(Rule& r, const std::u16string* endSource, bool allowA, bool allowG);
    std::shared_ptr<Scanner> compileWhile(Rule& r, const std::u16string* endSource, bool allowA, bool allowG);

    // theme
    Style themeMatch(const ScopeInfo* leaf, const AttrStack* parentPath);
    const ScopeInfo* scopeInfoWithTheme(const ScopeInfo* s);
    Ref<AttrStack> pushOne(const Ref<AttrStack>& target, const ScopeInfo* scope);
    uint32_t mergeAttributes(uint32_t existing, int tokenType, const Style* style);

    // tokenization (tm_tokenize.cpp)
    struct MatchResult {
        std::vector<Cap> caps;
        int ruleId = 0;
        bool priorityMatch = false;
        bool found = false;
    };
    struct Ctx;
    TokenizeResult tokenizeString(const LineStr& line, bool isFirstLine, int linePos, Ref<StateStack> stack, LineTokens& tokens, bool checkWhileConditions, int timeLimitMs);
    struct WhileCheck { Ref<StateStack> stack; int linePos; int anchorPos; bool isFirstLine; };
    WhileCheck checkWhileConditions(const LineStr& line, bool isFirstLine, int linePos, Ref<StateStack> stack, LineTokens& tokens);
    bool matchRuleOrInjections(const LineStr& line, bool isFirstLine, int linePos, const Ref<StateStack>& stack, int anchorPos, MatchResult& out);
    bool matchRule(const LineStr& line, bool isFirstLine, int linePos, const Ref<StateStack>& stack, int anchorPos, int limit, MatchResult& out);
    bool matchInjections(const LineStr& line, bool isFirstLine, int linePos, const Ref<StateStack>& stack, int anchorPos, int limit, MatchResult& out);
    void handleCaptures(const LineStr& line, bool isFirstLine, const Ref<StateStack>& stack, LineTokens& tokens, const std::vector<int>& captures, const std::vector<Cap>& caps);

    Registry& registry_;
    std::shared_ptr<const RawGrammar> root_;
    std::vector<std::unique_ptr<Rule>> rules_;
    std::unordered_map<const RawRule*, int> ruleIds_;
    std::deque<RawRule> tempRules_;
    std::deque<std::unique_ptr<Instance>> instances_;
    std::unordered_map<std::string, Instance*> included_;
    Instance* rootInst_ = nullptr;
    int rootId_ = 0;
    int lastRuleId_ = 0;
    std::vector<Injection> injections_;
    bool injectionsCollected_ = false;
    std::unordered_map<std::string, std::unique_ptr<ScopeInfo>> scopes_;
    RegexPool pool_;
    std::shared_ptr<const Theme> theme_;
    uint32_t themeEpoch_ = 0;
    uint32_t defaultMeta_ = 0;
    int collectDepth_ = 0;
    uint64_t serial_ = 0;
    Ref<StateStack> initial_;
    uint32_t initialEpoch_ = 0xFFFFFFFFu;
    std::string error_;
};

/// True when the UTF-16 text contains a right-to-left character (tokens are then not merged), see vscode-textmate containsRTL.
bool lineContainsRtl(const char16_t* s, int n);

}  // namespace tmg
