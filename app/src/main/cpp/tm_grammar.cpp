// Grammar: rule factory (include resolution), injections, scope/theme attribution, initial state.
#include "tm_grammar.hpp"

#include <algorithm>
#include <cstring>

namespace tmg {

namespace {

bool isWordC(char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'; }

bool matchesAt(const std::string& s, size_t i, const char* lit) {
    size_t n = strlen(lit);
    return s.compare(i, n, lit) == 0 && i + n <= s.size();
}

/// /\b(comment|string|regex|meta\.embedded)\b/ -> OptionalStandardTokenType
int standardTokenType(const std::string& s) {
    static const struct { const char* w; int type; } kAlts[] = {{"comment", 1}, {"string", 2}, {"regex", 3}, {"meta.embedded", 0}};
    for (size_t i = 0; i < s.size(); ++i) {
        if (i > 0 && isWordC(s[i - 1])) continue;
        for (auto& a : kAlts) {
            size_t n = strlen(a.w);
            if (i + n <= s.size() && s.compare(i, n, a.w) == 0 && (i + n == s.size() || !isWordC(s[i + n]))) return a.type;
        }
    }
    return attr::kTokenTypeNotSet;
}

/// /\$(\d+)|\${(\d+):\/(downcase|upcase)}/g
bool hasCaptureRefs(const std::string& s) {
    for (size_t i = 0; i + 1 < s.size(); ++i) {
        if (s[i] != '$') continue;
        if (s[i + 1] >= '0' && s[i + 1] <= '9') return true;
        if (s[i + 1] == '{') {
            size_t j = i + 2;
            while (j < s.size() && s[j] >= '0' && s[j] <= '9') ++j;
            if (j > i + 2 && (matchesAt(s, j, ":/downcase}") || matchesAt(s, j, ":/upcase}"))) return true;
        }
    }
    return false;
}

std::string replaceCaptures(const std::string& src, const char16_t* line, const std::vector<Cap>& caps) {
    std::string out;
    size_t n = src.size();
    for (size_t i = 0; i < n;) {
        if (src[i] != '$') { out.push_back(src[i++]); continue; }
        size_t j = i + 1;
        long idx = -1;
        int cmd = 0;   // 0 none, 1 downcase, 2 upcase
        size_t endPos = i;
        if (j < n && src[j] >= '0' && src[j] <= '9') {
            idx = 0;
            while (j < n && src[j] >= '0' && src[j] <= '9') { if (idx < 100000) idx = idx * 10 + (src[j] - '0'); ++j; }
            endPos = j;
        } else if (j < n && src[j] == '{') {
            size_t k = j + 1;
            long v = 0;
            size_t ds = k;
            while (k < n && src[k] >= '0' && src[k] <= '9') { if (v < 100000) v = v * 10 + (src[k] - '0'); ++k; }
            if (k > ds) {
                if (matchesAt(src, k, ":/downcase}")) { idx = v; cmd = 1; endPos = k + 11; }
                else if (matchesAt(src, k, ":/upcase}")) { idx = v; cmd = 2; endPos = k + 9; }
            }
        }
        if (idx < 0) { out.push_back(src[i++]); continue; }
        if (idx < static_cast<long>(caps.size())) {
            const Cap& c = caps[idx];
            std::string r = toU8(line + c.start, static_cast<size_t>(c.end - c.start));
            size_t lead = 0;
            while (lead < r.size() && r[lead] == '.') ++lead;
            r.erase(0, lead);
            if (cmd == 1) for (auto& ch : r) { if (ch >= 'A' && ch <= 'Z') ch = static_cast<char>(ch + 32); }
            else if (cmd == 2) for (auto& ch : r) { if (ch >= 'a' && ch <= 'z') ch = static_cast<char>(ch - 32); }
            out += r;
        } else {
            out.append(src, i, endPos - i);
        }
        i = endPos;
    }
    return out;
}

bool parentsMatch(const AttrStack* path, const std::vector<std::string>& parents) {
    if (parents.empty()) return true;
    for (size_t index = 0; index < parents.size(); ++index) {
        const std::string* pattern = &parents[index];
        bool mustMatch = false;
        if (*pattern == ">") {
            if (index == parents.size() - 1) return false;
            pattern = &parents[++index];
            mustMatch = true;
        }
        while (path) {
            if (matchesScope(path->scope->name, *pattern)) break;
            if (mustMatch) return false;
            path = path->parent.get();
        }
        if (!path) return false;
        path = path->parent.get();
    }
    return true;
}

}  // namespace

Grammar::Grammar(Registry& registry, std::shared_ptr<const RawGrammar> root) : registry_(registry), root_(std::move(root)) {
    rules_.resize(1);
}

Grammar::~Grammar() = default;

void Grammar::useTheme(const Registry::ThemeSnapshot& snap) {
    theme_ = snap.theme;
    themeEpoch_ = snap.epoch;
}

const ScopeInfo* Grammar::intern(const std::string& name) {
    auto it = scopes_.find(name);
    if (it != scopes_.end()) return it->second.get();
    auto s = std::make_unique<ScopeInfo>();
    s->name = name;
    s->tokenType = standardTokenType(name);
    const ScopeInfo* p = s.get();
    scopes_.emplace(name, std::move(s));
    return p;
}

const ScopeInfo* Grammar::scopeInfoWithTheme(const ScopeInfo* s) {
    ScopeInfo* m = const_cast<ScopeInfo*>(s);
    if (m->themeEpoch != themeEpoch_) {
        m->rules = theme_->matchRoot(m->name);
        m->themeEpoch = themeEpoch_;
    }
    return s;
}

Style Grammar::themeMatch(const ScopeInfo* leaf, const AttrStack* parentPath) {
    const ScopeInfo* s = scopeInfoWithTheme(leaf);
    for (const TrieRule& r : s->rules) {
        if (parentsMatch(parentPath, r.parentScopes)) return Style{r.fontStyle, r.fg, r.bg};
    }
    return theme_->defaults();
}

uint32_t Grammar::mergeAttributes(uint32_t existing, int tokenType, const Style* style) {
    int fs = FS_NotSet, fg = 0, bg = 0;
    if (style) { fs = style->fontStyle; fg = style->fg; bg = style->bg; }
    return attr::set(existing, tokenType, fs, fg, bg);
}

Ref<AttrStack> Grammar::pushOne(const Ref<AttrStack>& target, const ScopeInfo* scope) {
    Style st = themeMatch(scope, target.get());
    auto n = makeRef<AttrStack>();
    n->parent = target;
    n->scope = scope;
    n->attrs = mergeAttributes(target ? target->attrs : 0, scope->tokenType, &st);
    return n;
}

Ref<AttrStack> Grammar::pushAttributed(const Ref<AttrStack>& base, const std::string& scopePath) {
    if (scopePath.find(' ') == std::string::npos) return pushOne(base, intern(scopePath));
    Ref<AttrStack> result = base;
    size_t st = 0;
    for (;;) {
        size_t i = scopePath.find(' ', st);
        if (i == std::string::npos) { result = pushOne(result, intern(scopePath.substr(st))); break; }
        result = pushOne(result, intern(scopePath.substr(st, i - st)));
        st = i + 1;
    }
    return result;
}

Ref<AttrStack> Grammar::pushScopes(const Ref<AttrStack>& base, const std::vector<const ScopeInfo*>& scopes) {
    Ref<AttrStack> r = base;
    for (const ScopeInfo* s : scopes) r = pushOne(r, s);
    return r;
}

Ref<AttrStack> Grammar::pushName(const Ref<AttrStack>& base, const Rule& r, const char16_t* line, const std::vector<Cap>& caps) {
    if (!r.hasName) return base;
    if (!r.nameCapturing) return pushScopes(base, r.nameScopes);
    return pushAttributed(base, replaceCaptures(r.nameRaw, line, caps));
}

Ref<AttrStack> Grammar::pushContentName(const Ref<AttrStack>& base, const Rule& r, const char16_t* line, const std::vector<Cap>& caps) {
    if (!r.hasContentName) return base;
    if (!r.contentNameCapturing) return pushScopes(base, r.contentScopes);
    return pushAttributed(base, replaceCaptures(r.contentRaw, line, caps));
}

std::vector<std::string> Grammar::scopeNames(const AttrStack* s) {
    std::vector<std::string> out;
    for (; s; s = s->parent.get()) out.push_back(s->scope->name);
    std::reverse(out.begin(), out.end());
    return out;
}

// ---------------------------------------------------------------- rule factory

void Grammar::initNames(Rule& r, const std::string& name, const std::string& contentName) {
    if (!name.empty()) {
        r.hasName = true;
        r.nameRaw = name;
        r.nameCapturing = hasCaptureRefs(name);
        if (!r.nameCapturing) {
            size_t st = 0;
            for (;;) {
                size_t i = name.find(' ', st);
                if (i == std::string::npos) { r.nameScopes.push_back(intern(name.substr(st))); break; }
                r.nameScopes.push_back(intern(name.substr(st, i - st)));
                st = i + 1;
            }
        }
    }
    if (!contentName.empty()) {
        r.hasContentName = true;
        r.contentRaw = contentName;
        r.contentNameCapturing = hasCaptureRefs(contentName);
        if (!r.contentNameCapturing) {
            size_t st = 0;
            for (;;) {
                size_t i = contentName.find(' ', st);
                if (i == std::string::npos) { r.contentScopes.push_back(intern(contentName.substr(st))); break; }
                r.contentScopes.push_back(intern(contentName.substr(st, i - st)));
                st = i + 1;
            }
        }
    }
}

int Grammar::registerRuleSlot() {
    int id = ++lastRuleId_;
    if (static_cast<int>(rules_.size()) <= id) rules_.resize(id + 1);
    return id;
}

int Grammar::createCaptureRule(const RawRule* desc, int retokenizeId) {
    int id = registerRuleSlot();
    auto r = std::make_unique<Rule>();
    r->kind = RuleKind::Capture;
    r->id = id;
    r->retokenizeId = retokenizeId;
    initNames(*r, desc->name, desc->contentName);
    rules_[id] = std::move(r);
    return id;
}

std::vector<int> Grammar::compileCaptures(const std::vector<RawCapture>& caps, RepoPtr repository) {
    std::vector<int> r;
    if (caps.empty()) return r;
    int maxId = 0;
    for (auto& c : caps) maxId = std::max(maxId, c.index);
    r.assign(static_cast<size_t>(maxId) + 1, 0);
    for (auto& c : caps) {
        int retokenize = 0;
        if (c.rule->hasPatterns) retokenize = getCompiledRuleId(c.rule, repository);
        r[c.index] = createCaptureRule(c.rule, retokenize);
    }
    return r;
}

Grammar::Instance* Grammar::initGrammar(const std::shared_ptr<const RawGrammar>& raw, const RawRule* base) {
    auto inst = std::make_unique<Instance>();
    inst->raw = raw;
    inst->self.hasPatterns = raw->root.hasPatterns;
    inst->self.patterns = raw->root.patterns;
    inst->self.name = raw->scopeName;
    auto repo = std::make_shared<Repo>();
    for (auto& kv : raw->repository->items) (*repo)[kv.first] = kv.second;
    (*repo)["$self"] = &inst->self;
    (*repo)["$base"] = base ? base : &inst->self;
    inst->repo = repo;
    Instance* p = inst.get();
    instances_.push_back(std::move(inst));
    return p;
}

Grammar::Instance* Grammar::getExternalGrammar(const std::string& scopeName, const RepoPtr& repository) {
    auto it = included_.find(scopeName);
    if (it != included_.end()) return it->second;
    const RawRule* base = nullptr;
    if (repository) {
        auto b = repository->find("$base");
        if (b != repository->end()) base = b->second;
    }
    if (rootInst_ && scopeName == root_->scopeName && (!base || base == &rootInst_->self)) return rootInst_;
    std::shared_ptr<const RawGrammar> raw = registry_.lookup(scopeName);
    if (!raw) return nullptr;
    Instance* inst = initGrammar(raw, base);
    included_[scopeName] = inst;
    return inst;
}

Grammar::PatternsResult Grammar::compilePatterns(const std::vector<RawRule*>* patterns, const RepoPtr& repository) {
    PatternsResult res;
    res.hasMissing = false;
    size_t count = patterns ? patterns->size() : 0;
    if (patterns) {
        for (size_t i = 0; i < patterns->size(); ++i) {
            const RawRule* pattern = (*patterns)[i];
            int ruleId = -1;
            if (!pattern->include.empty()) {
                const std::string& inc = pattern->include;
                if (inc == "$base" || inc == "$self") {
                    auto it = repository->find(inc);
                    if (it != repository->end()) ruleId = getCompiledRuleId(it->second, repository);
                } else {
                    size_t sharp = inc.find('#');
                    if (sharp == 0) {
                        auto it = repository->find(inc.substr(1));
                        if (it != repository->end()) ruleId = getCompiledRuleId(it->second, repository);
                    } else {
                        std::string scope = sharp == std::string::npos ? inc : inc.substr(0, sharp);
                        Instance* ext = getExternalGrammar(scope, repository);
                        if (ext) {
                            if (sharp != std::string::npos) {
                                auto it = ext->repo->find(inc.substr(sharp + 1));
                                if (it != ext->repo->end()) ruleId = getCompiledRuleId(it->second, ext->repo);
                            } else {
                                auto it = ext->repo->find("$self");
                                ruleId = getCompiledRuleId(it->second, ext->repo);
                            }
                        }
                    }
                }
            } else {
                ruleId = getCompiledRuleId(pattern, repository);
            }
            if (ruleId != -1) {
                Rule* rule = getRule(ruleId);
                bool skip = false;
                if (rule && (rule->kind == RuleKind::IncludeOnly || rule->kind == RuleKind::BeginEnd || rule->kind == RuleKind::BeginWhile)) {
                    if (rule->hasMissingPatterns && rule->patterns.empty()) skip = true;
                }
                if (skip) continue;
                res.ids.push_back(ruleId);
            }
        }
    }
    res.hasMissing = count != res.ids.size();
    return res;
}

int Grammar::getCompiledRuleId(const RawRule* desc, RepoPtr repository) {
    auto found = ruleIds_.find(desc);
    if (found != ruleIds_.end()) return found->second;
    int id = registerRuleSlot();
    ruleIds_[desc] = id;
    auto r = std::make_unique<Rule>();
    r->id = id;
    if (!desc->match.empty()) {
        r->kind = RuleKind::Match;
        initNames(*r, desc->name, std::string());
        r->begin = std::make_unique<RegExpSource>(desc->match, id);
        r->captures = compileCaptures(desc->captures, repository);
    } else if (!desc->hasBegin) {
        r->kind = RuleKind::IncludeOnly;
        initNames(*r, desc->name, desc->contentName);
        RepoPtr repo = repository;
        if (desc->repository) {
            auto merged = std::make_shared<Repo>(*repository);
            for (auto& kv : desc->repository->items) (*merged)[kv.first] = kv.second;
            repo = merged;
        }
        std::vector<RawRule*> tmpPatterns;
        const std::vector<RawRule*>* pats = nullptr;
        if (desc->hasPatterns) pats = &desc->patterns;
        else if (!desc->include.empty()) {
            tempRules_.emplace_back();
            tempRules_.back().include = desc->include;
            tmpPatterns.push_back(&tempRules_.back());
            pats = &tmpPatterns;
        }
        PatternsResult pr = compilePatterns(pats, repo);
        r->patterns = std::move(pr.ids);
        r->hasMissingPatterns = pr.hasMissing;
    } else if (!desc->whileRe.empty()) {
        r->kind = RuleKind::BeginWhile;
        initNames(*r, desc->name, desc->contentName);
        r->begin = std::make_unique<RegExpSource>(desc->begin, id);
        r->captures = compileCaptures(desc->hasBeginCaptures ? desc->beginCaptures : desc->captures, repository);
        r->endOrWhile = std::make_unique<RegExpSource>(desc->whileRe, kWhileRuleId);
        r->endHasBackReferences = r->endOrWhile->hasBackReferences;
        r->endCaptures = compileCaptures(desc->hasWhileCaptures ? desc->whileCaptures : desc->captures, repository);
        PatternsResult pr = compilePatterns(desc->hasPatterns ? &desc->patterns : nullptr, repository);
        r->patterns = std::move(pr.ids);
        r->hasMissingPatterns = pr.hasMissing;
    } else {
        r->kind = RuleKind::BeginEnd;
        initNames(*r, desc->name, desc->contentName);
        r->begin = std::make_unique<RegExpSource>(desc->begin, id);
        r->captures = compileCaptures(desc->hasBeginCaptures ? desc->beginCaptures : desc->captures, repository);
        r->endOrWhile = std::make_unique<RegExpSource>(desc->end, kEndRuleId);
        r->endHasBackReferences = r->endOrWhile->hasBackReferences;
        r->endCaptures = compileCaptures(desc->hasEndCaptures ? desc->endCaptures : desc->captures, repository);
        r->applyEndPatternLast = desc->applyEndPatternLast;
        PatternsResult pr = compilePatterns(desc->hasPatterns ? &desc->patterns : nullptr, repository);
        r->patterns = std::move(pr.ids);
        r->hasMissingPatterns = pr.hasMissing;
    }
    rules_[id] = std::move(r);
    return id;
}

// ---------------------------------------------------------------- injections & prepare

void Grammar::collectInjection(const std::string& selector, const RawRule* rule, Instance* inst) {
    std::vector<MatcherWithPriority> matchers = createMatchers(selector);
    int ruleId = getCompiledRuleId(rule, inst->repo);
    for (auto& m : matchers) injections_.push_back({m.matcher, m.priority, ruleId});
}

void Grammar::collectInjections() {
    if (injectionsCollected_) return;
    injectionsCollected_ = true;
    for (auto& kv : root_->injections) collectInjection(kv.first, kv.second, rootInst_);
    for (const std::string& injScope : registry_.injectionsFor(root_->scopeName)) {
        Instance* inst = getExternalGrammar(injScope, nullptr);
        if (!inst) continue;
        const std::string& selector = inst->raw->injectionSelector;
        if (!selector.empty()) collectInjection(selector, &inst->raw->root, inst);
    }
    std::stable_sort(injections_.begin(), injections_.end(), [](const Injection& a, const Injection& b) { return a.priority < b.priority; });
}

bool Grammar::prepare(std::string& err) {
    if (rootId_ != 0) return true;
    if (!theme_) useTheme(registry_.themeSnapshot());
    rootInst_ = initGrammar(root_, nullptr);
    RepoPtr repo = rootInst_->repo;
    rootId_ = getCompiledRuleId(&rootInst_->self, repo);
    collectInjections();
    if (!getRule(rootId_)) { err = "failed to compile root rule"; return false; }
    return true;
}

Ref<StateStack> Grammar::initialState() {
    if (initial_ && initialEpoch_ == themeEpoch_) return initial_;
    const Style& ds = theme_->defaults();
    defaultMeta_ = attr::set(0, attr::kTokenTypeNotSet, ds.fontStyle, ds.fg, ds.bg);
    Rule* root = getRule(rootId_);
    Ref<AttrStack> scopeList;
    auto node = makeRef<AttrStack>();
    if (root && root->hasName) {
        const ScopeInfo* info = intern(root->nameRaw);
        Style st = themeMatch(info, nullptr);
        node->scope = info;
        node->attrs = mergeAttributes(defaultMeta_, info->tokenType, &st);
    } else {
        node->scope = intern("unknown");
        node->attrs = defaultMeta_;
    }
    scopeList = node;
    auto st = makeRef<StateStack>();
    st->ruleId = rootId_;
    st->nameScopes = scopeList;
    st->contentScopes = scopeList;
    st->depth = 1;
    initial_ = st;
    initialEpoch_ = themeEpoch_;
    return initial_;
}

// ---------------------------------------------------------------- pattern collection / compilation

void Grammar::collectPatterns(const Rule& r, RegExpSourceList& out, int depth) {
    if (depth > 200) return;   // include-only cycle guard (vscode-textmate would overflow the stack)
    switch (r.kind) {
        case RuleKind::Match: out.push(r.begin.get()); break;
        case RuleKind::IncludeOnly:
            for (int p : r.patterns) { Rule* c = getRule(p); if (c) collectPatterns(*c, out, depth + 1); }
            break;
        case RuleKind::BeginEnd:
        case RuleKind::BeginWhile: out.push(r.begin.get()); break;
        case RuleKind::Capture: break;
    }
}

std::shared_ptr<Scanner> Grammar::compileRule(Rule& r, const std::u16string* endSource, bool allowA, bool allowG) {
    if (!r.list) {
        r.list = std::make_unique<RegExpSourceList>();
        if (r.kind == RuleKind::Match) r.list->push(r.begin.get());
        else if (r.kind == RuleKind::BeginEnd) {
            for (int p : r.patterns) { Rule* c = getRule(p); if (c) collectPatterns(*c, *r.list, 0); }
            RegExpSource* end = r.endOrWhile.get();
            if (r.applyEndPatternLast) {
                if (end->hasBackReferences) r.list->pushOwned(std::make_unique<RegExpSource>(end->source, end->ruleId)); else r.list->push(end);
            } else {
                if (end->hasBackReferences) r.list->unshiftOwned(std::make_unique<RegExpSource>(end->source, end->ruleId)); else r.list->unshift(end);
            }
        } else {
            // IncludeOnly / BeginWhile: only the nested patterns (a BeginWhile's own begin regex is matched by its parent)
            for (int p : r.patterns) { Rule* c = getRule(p); if (c) collectPatterns(*c, *r.list, 0); }
        }
    }
    if (r.kind == RuleKind::BeginEnd && r.endOrWhile->hasBackReferences) {
        static const std::u16string kEmpty;
        r.list->setSource(r.applyEndPatternLast ? r.list->length() - 1 : 0, endSource ? *endSource : kEmpty);
    }
    return r.list->compileAG(pool_, allowA, allowG);
}

std::shared_ptr<Scanner> Grammar::compileWhile(Rule& r, const std::u16string* endSource, bool allowA, bool allowG) {
    if (!r.whileList) {
        r.whileList = std::make_unique<RegExpSourceList>();
        RegExpSource* w = r.endOrWhile.get();
        if (w->hasBackReferences) r.whileList->pushOwned(std::make_unique<RegExpSource>(w->source, w->ruleId)); else r.whileList->push(w);
    }
    if (r.endOrWhile->hasBackReferences) {
        static const std::u16string kEmpty;
        r.whileList->setSource(0, endSource ? *endSource : kEmpty);
    }
    return r.whileList->compileAG(pool_, allowA, allowG);
}

}  // namespace tmg
