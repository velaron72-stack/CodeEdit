// Port of vscode-textmate tokenizeString.ts and the line driver of grammar.ts.
#include <climits>

#include "tm_grammar.hpp"

namespace tmg {

namespace {

Ref<StateStack> pushState(const Ref<StateStack>& parent, int ruleId, int enterPos, int anchorPos, bool capturedEol,
                          std::shared_ptr<const std::u16string> endRule, Ref<AttrStack> name, Ref<AttrStack> content) {
    auto s = makeRef<StateStack>();
    s->parent = parent;
    s->ruleId = ruleId;
    s->enterPos = enterPos;
    s->anchorPos = anchorPos;
    s->beginRuleCapturedEOL = capturedEol;
    s->endRule = std::move(endRule);
    s->nameScopes = std::move(name);
    s->contentScopes = std::move(content);
    s->depth = parent ? parent->depth + 1 : 1;
    return s;
}

Ref<StateStack> withContentScopes(const Ref<StateStack>& s, const Ref<AttrStack>& content) {
    if (s->contentScopes == content) return s;
    return pushState(s->parent, s->ruleId, s->enterPos, s->anchorPos, s->beginRuleCapturedEOL, s->endRule, s->nameScopes, content);
}

Ref<StateStack> withEndRule(const Ref<StateStack>& s, const std::u16string& endRule) {
    if (s->endRule && *s->endRule == endRule) return s;
    return pushState(s->parent, s->ruleId, s->enterPos, s->anchorPos, s->beginRuleCapturedEOL,
                     std::make_shared<const std::u16string>(endRule), s->nameScopes, s->contentScopes);
}

bool hasSameRuleAs(const StateStack* self, const StateStack* other) {
    for (const StateStack* el = self; el && el->enterPos == other->enterPos; el = el->parent.get())
        if (el->ruleId == other->ruleId) return true;
    return false;
}

void resetState(StateStack* s) {
    for (; s; s = s->parent.get()) { s->enterPos = -1; s->anchorPos = -1; }
}

Ref<StateStack> safePop(const Ref<StateStack>& s) { return s->parent ? s->parent : s; }

// /RTL/ (see vscode-textmate utils.ts containsRTL)
struct R16 { char16_t lo, hi; };
const R16 kRtlBmp[] = {
    {0x05BE, 0x05BE}, {0x05C0, 0x05C0}, {0x05C3, 0x05C3}, {0x05C6, 0x05C6}, {0x05D0, 0x05F4}, {0x0608, 0x0608}, {0x060B, 0x060B},
    {0x060D, 0x060D}, {0x061B, 0x064A}, {0x066D, 0x066F}, {0x0671, 0x06D5}, {0x06E5, 0x06E6}, {0x06EE, 0x06EF}, {0x06FA, 0x0710},
    {0x0712, 0x072F}, {0x074D, 0x07A5}, {0x07B1, 0x07EA}, {0x07F4, 0x07F5}, {0x07FA, 0x07FA}, {0x07FE, 0x0815}, {0x081A, 0x081A},
    {0x0824, 0x0824}, {0x0828, 0x0828}, {0x0830, 0x0858}, {0x085E, 0x088E}, {0x08A0, 0x08C9}, {0x200F, 0x200F}, {0xFB1D, 0xFB1D},
    {0xFB1F, 0xFB28}, {0xFB2A, 0xFD3D}, {0xFD50, 0xFDC7}, {0xFDF0, 0xFDFC}, {0xFE70, 0xFEFC}};
struct SurrRange { char16_t hi, loA, loB; };
const SurrRange kRtlSurr[] = {
    {0xD802, 0xDC00, 0xDD1B}, {0xD802, 0xDD20, 0xDE00}, {0xD802, 0xDE10, 0xDE35}, {0xD802, 0xDE40, 0xDEE4}, {0xD802, 0xDEEB, 0xDF35},
    {0xD802, 0xDF40, 0xDFFF}, {0xD803, 0xDC00, 0xDD23}, {0xD803, 0xDE80, 0xDEA9}, {0xD803, 0xDEAD, 0xDF45}, {0xD803, 0xDF51, 0xDF81},
    {0xD803, 0xDF86, 0xDFF6}, {0xD83A, 0xDC00, 0xDCCF}, {0xD83A, 0xDD00, 0xDD43}, {0xD83A, 0xDD4B, 0xDFFF}, {0xD83B, 0xDC00, 0xDEBB}};

bool containsRTL(const char16_t* s, int n) {
    for (int i = 0; i < n; ++i) {
        char16_t c = s[i];
        if (c < 0x05BE) continue;
        if (c >= 0xD800 && c <= 0xDBFF) {
            if (i + 1 < n) {
                char16_t lo = s[i + 1];
                for (auto& r : kRtlSurr) if (r.hi == c && lo >= r.loA && lo <= r.loB) return true;
            }
            continue;
        }
        for (auto& r : kRtlBmp) if (c >= r.lo && c <= r.hi) return true;
    }
    return false;
}

}  // namespace

TokenizeResult Grammar::tokenizeLine(const char16_t* text, int len, const Ref<StateStack>& prev, bool forceNotFirstLine, LineTokens& out, int timeLimitMs) {
    std::u16string line;
    line.reserve(static_cast<size_t>(len) + 1);
    line.assign(text, static_cast<size_t>(len));
    line.push_back(u'\n');
    Ref<StateStack> st;
    bool isFirst;
    if (!prev) {
        st = initialState();
        isFirst = !forceNotFirstLine;
    } else {
        st = prev;
        isFirst = false;
        resetState(st.get());
    }
    LineStr ls{line.data(), static_cast<int>(line.size()), ++serial_};
    TokenizeResult r = tokenizeString(ls, isFirst, 0, st, out, true, timeLimitMs);
    out.finish(r.state->contentScopes.get(), ls.len);
    return r;
}

bool lineContainsRtl(const char16_t* s, int n) { return containsRTL(s, n); }

TokenizeResult Grammar::tokenizeString(const LineStr& line, bool isFirstLine, int linePos, Ref<StateStack> stack, LineTokens& tokens,
                                       bool checkWhile, int timeLimitMs) {
    const int lineLength = line.len;
    bool stop = false;
    int anchorPosition = -1;
    if (checkWhile) {
        WhileCheck w = checkWhileConditions(line, isFirstLine, linePos, stack, tokens);
        stack = w.stack;
        linePos = w.linePos;
        isFirstLine = w.isFirstLine;
        anchorPosition = w.anchorPos;
    }
    auto startTime = std::chrono::steady_clock::now();
    while (!stop) {
        if (timeLimitMs != 0) {
            auto el = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - startTime).count();
            if (el > timeLimitMs) return {stack, true};
        }
        MatchResult r;
        if (!matchRuleOrInjections(line, isFirstLine, linePos, stack, anchorPosition, r)) {
            tokens.produce(stack->contentScopes.get(), lineLength);
            stop = true;
            break;
        }
        const std::vector<Cap>& caps = r.caps;
        const int matchedRuleId = r.ruleId;
        const bool hasAdvanced = !caps.empty() ? caps[0].end > linePos : false;

        if (matchedRuleId == kEndRuleId) {
            Rule* popped = getRule(stack->ruleId);
            tokens.produce(stack->contentScopes.get(), caps[0].start);
            stack = withContentScopes(stack, stack->nameScopes);
            handleCaptures(line, isFirstLine, stack, tokens, popped->endCaptures, caps);
            tokens.produce(stack->contentScopes.get(), caps[0].end);
            Ref<StateStack> poppedState = stack;
            stack = stack->parent;
            anchorPosition = poppedState->anchorPos;
            if (!hasAdvanced && poppedState->enterPos == linePos) {
                // Grammar pushed & popped a rule without advancing: assume a grammar bug and stay in this state.
                stack = poppedState;
                tokens.produce(stack->contentScopes.get(), lineLength);
                stop = true;
                break;
            }
        } else {
            Rule* rule = getRule(matchedRuleId);
            tokens.produce(stack->contentScopes.get(), caps[0].start);
            Ref<StateStack> beforePush = stack;
            Ref<AttrStack> nameScopes = pushName(stack->contentScopes, *rule, line.data, caps);
            stack = pushState(stack, matchedRuleId, linePos, anchorPosition, caps[0].end == lineLength, nullptr, nameScopes, nameScopes);

            if (rule->kind == RuleKind::BeginEnd || rule->kind == RuleKind::BeginWhile) {
                handleCaptures(line, isFirstLine, stack, tokens, rule->captures, caps);
                tokens.produce(stack->contentScopes.get(), caps[0].end);
                anchorPosition = caps[0].end;
                Ref<AttrStack> contentScopes = pushContentName(nameScopes, *rule, line.data, caps);
                stack = withContentScopes(stack, contentScopes);
                if (rule->endHasBackReferences) stack = withEndRule(stack, rule->endOrWhile->resolveBackReferences(line.data, caps));
                if (!hasAdvanced && hasSameRuleAs(beforePush.get(), stack.get())) {
                    // Grammar pushed the same rule without advancing
                    stack = stack->parent;
                    tokens.produce(stack->contentScopes.get(), lineLength);
                    stop = true;
                    break;
                }
            } else {
                handleCaptures(line, isFirstLine, stack, tokens, rule->captures, caps);
                tokens.produce(stack->contentScopes.get(), caps[0].end);
                stack = stack->parent;   // a match rule pops immediately
                if (!hasAdvanced) {
                    stack = safePop(stack);
                    tokens.produce(stack->contentScopes.get(), lineLength);
                    stop = true;
                    break;
                }
            }
        }
        if (caps[0].end > linePos) {
            linePos = caps[0].end;
            isFirstLine = false;
        }
    }
    return {stack, false};
}

Grammar::WhileCheck Grammar::checkWhileConditions(const LineStr& line, bool isFirstLine, int linePos, Ref<StateStack> stack, LineTokens& tokens) {
    int anchorPosition = stack->beginRuleCapturedEOL ? 0 : -1;
    struct WR { Ref<StateStack> stack; Rule* rule; };
    std::vector<WR> whileRules;
    for (StateStack* node = stack.get(); node; node = node->parent.get()) {
        Rule* r = getRule(node->ruleId);
        if (r && r->kind == RuleKind::BeginWhile) whileRules.push_back({Ref<StateStack>(node), r});
    }
    while (!whileRules.empty()) {
        WR wr = whileRules.back();
        whileRules.pop_back();
        std::shared_ptr<Scanner> sc = compileWhile(*wr.rule, wr.stack->endRule.get(), isFirstLine, linePos == anchorPosition);
        std::vector<Cap> caps;
        int idx = sc->find(line, linePos, line.len, caps);
        if (idx >= 0) {
            if (sc->ruleIdAt(idx) != kWhileRuleId) { stack = wr.stack->parent; break; }
            if (!caps.empty()) {
                tokens.produce(wr.stack->contentScopes.get(), caps[0].start);
                handleCaptures(line, isFirstLine, wr.stack, tokens, wr.rule->endCaptures, caps);
                tokens.produce(wr.stack->contentScopes.get(), caps[0].end);
                anchorPosition = caps[0].end;
                if (caps[0].end > linePos) { linePos = caps[0].end; isFirstLine = false; }
            }
        } else {
            stack = wr.stack->parent;
            break;
        }
    }
    return {stack, linePos, anchorPosition, isFirstLine};
}

bool Grammar::matchRuleOrInjections(const LineStr& line, bool isFirstLine, int linePos, const Ref<StateStack>& stack, int anchorPosition, MatchResult& out) {
    MatchResult main;
    bool hasMain = matchRule(line, isFirstLine, linePos, stack, anchorPosition, line.len, main);
    if (injections_.empty()) {
        if (hasMain) out = std::move(main);
        return hasMain;
    }
    MatchResult inj;
    int limit = hasMain ? main.caps[0].start : line.len;
    bool hasInj = matchInjections(line, isFirstLine, linePos, stack, anchorPosition, limit, inj);
    if (!hasInj) {
        if (hasMain) out = std::move(main);
        return hasMain;
    }
    if (!hasMain) { out = std::move(inj); return true; }
    int matchScore = main.caps[0].start;
    int injScore = inj.caps[0].start;
    if (injScore < matchScore || (inj.priorityMatch && injScore == matchScore)) { out = std::move(inj); return true; }
    out = std::move(main);
    return true;
}

bool Grammar::matchRule(const LineStr& line, bool isFirstLine, int linePos, const Ref<StateStack>& stack, int anchorPosition, int limit, MatchResult& out) {
    Rule* rule = getRule(stack->ruleId);
    if (!rule) return false;
    std::shared_ptr<Scanner> sc = compileRule(*rule, stack->endRule.get(), isFirstLine, linePos == anchorPosition);
    int idx = sc->find(line, linePos, limit, out.caps);
    if (idx < 0) return false;
    out.ruleId = sc->ruleIdAt(idx);
    out.found = true;
    return true;
}

bool Grammar::matchInjections(const LineStr& line, bool isFirstLine, int linePos, const Ref<StateStack>& stack, int anchorPosition, int limit, MatchResult& out) {
    int bestRating = INT_MAX;
    bool have = false;
    ScopeNames scopes;
    {
        std::vector<const std::string*> rev;
        for (const AttrStack* s = stack->contentScopes.get(); s; s = s->parent.get()) rev.push_back(&s->scope->name);
        scopes.assign(rev.rbegin(), rev.rend());
    }
    std::vector<Cap> caps;
    for (const Injection& inj : injections_) {
        if (!inj.matcher(scopes)) continue;
        Rule* rule = getRule(inj.ruleId);
        if (!rule) continue;
        std::shared_ptr<Scanner> sc = compileRule(*rule, nullptr, isFirstLine, linePos == anchorPosition);
        int idx = sc->find(line, linePos, limit, caps);
        if (idx < 0) continue;
        int rating = caps[0].start;
        if (rating >= bestRating) continue;   // injections are sorted by priority: an earlier equal match wins
        bestRating = rating;
        out.caps = caps;
        out.ruleId = sc->ruleIdAt(idx);
        out.priorityMatch = inj.priority == -1;
        out.found = true;
        have = true;
        if (bestRating == linePos) break;
    }
    return have;
}

void Grammar::handleCaptures(const LineStr& line, bool isFirstLine, const Ref<StateStack>& stack, LineTokens& tokens, const std::vector<int>& captures, const std::vector<Cap>& caps) {
    if (captures.empty()) return;
    const size_t len = std::min(captures.size(), caps.size());
    struct Local { Ref<AttrStack> scopes; int endPos; };
    std::vector<Local> localStack;
    const int maxEnd = caps[0].end;
    for (size_t i = 0; i < len; ++i) {
        int captureRuleId = captures[i];
        if (captureRuleId == 0) continue;
        const Cap& ci = caps[i];
        if (ci.end - ci.start == 0) continue;
        if (ci.start > maxEnd) break;
        while (!localStack.empty() && localStack.back().endPos <= ci.start) {
            tokens.produce(localStack.back().scopes.get(), localStack.back().endPos);
            localStack.pop_back();
        }
        if (!localStack.empty()) tokens.produce(localStack.back().scopes.get(), ci.start);
        else tokens.produce(stack->contentScopes.get(), ci.start);

        Rule* cr = getRule(captureRuleId);
        if (cr->retokenizeId) {
            Ref<AttrStack> nameScopes = pushName(stack->contentScopes, *cr, line.data, caps);
            Ref<AttrStack> contentScopes = pushContentName(nameScopes, *cr, line.data, caps);
            Ref<StateStack> clone = pushState(stack, cr->retokenizeId, ci.start, -1, false, nullptr, nameScopes, contentScopes);
            LineStr sub{line.data, ci.end, ++serial_};
            tokenizeString(sub, isFirstLine && ci.start == 0, ci.start, clone, tokens, false, 0);
            continue;
        }
        if (cr->hasName) {
            const Ref<AttrStack>& base = localStack.empty() ? stack->contentScopes : localStack.back().scopes;
            Ref<AttrStack> list = pushName(base, *cr, line.data, caps);
            localStack.push_back({list, ci.end});
        }
    }
    while (!localStack.empty()) {
        tokens.produce(localStack.back().scopes.get(), localStack.back().endPos);
        localStack.pop_back();
    }
}

}  // namespace tmg
