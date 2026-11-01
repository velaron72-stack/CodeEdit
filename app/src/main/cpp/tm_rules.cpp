// RegExpSource / RegExpSourceList / attribute encoding / LineTokens (vscode-textmate rule.ts, encodedTokenAttributes.ts, grammar.ts).
#include "tm_grammar.hpp"

namespace tmg {

namespace attr {

uint32_t set(uint32_t existing, int tokenType, int fontStyle, int fg, int bg) {
    uint32_t type = (existing & 0x300u) >> 8;
    uint32_t bal = (existing & 0x400u) >> 10;
    uint32_t fs = (existing & 0x7800u) >> 11;
    uint32_t f = (existing & 0xFF8000u) >> 15;
    uint32_t b = (existing & 0xFF000000u) >> 24;
    uint32_t lang = existing & 0xFFu;
    if (tokenType != kTokenTypeNotSet) type = static_cast<uint32_t>(tokenType) & 3u;
    if (fontStyle != FS_NotSet) fs = static_cast<uint32_t>(fontStyle) & 0xFu;
    if (fg > 0 && fg <= 511) f = static_cast<uint32_t>(fg);
    if (bg > 0 && bg <= 255) b = static_cast<uint32_t>(bg);
    return (lang) | (type << 8) | (bal << 10) | (fs << 11) | (f << 15) | (b << 24);
}

}  // namespace attr

// ---------------------------------------------------------------- RegExpSource

namespace {

bool hasBackRef(const std::u16string& s) {
    for (size_t i = 0; i + 1 < s.size(); ++i)
        if (s[i] == u'\\' && s[i + 1] >= u'0' && s[i + 1] <= u'9') return true;
    return false;
}

}  // namespace

RegExpSource::RegExpSource(const std::u16string& regExpSource, int rid) : ruleId(rid) {
    if (!regExpSource.empty()) {
        size_t len = regExpSource.size();
        size_t lastPushedPos = 0;
        std::u16string output;
        bool anchor = false;
        for (size_t pos = 0; pos < len; ++pos) {
            char16_t ch = regExpSource[pos];
            if (ch == u'\\') {
                if (pos + 1 < len) {
                    char16_t nextCh = regExpSource[pos + 1];
                    if (nextCh == u'z') {
                        output.append(regExpSource, lastPushedPos, pos - lastPushedPos);
                        output.append(u"$(?!\\n)(?<!\\n)");
                        lastPushedPos = pos + 2;
                    } else if (nextCh == u'A' || nextCh == u'G') {
                        anchor = true;
                    }
                    ++pos;
                }
            }
        }
        hasAnchor = anchor;
        if (lastPushedPos == 0) source = regExpSource;
        else {
            output.append(regExpSource, lastPushedPos, len - lastPushedPos);
            source = std::move(output);
        }
    } else {
        hasAnchor = false;
        source = regExpSource;
    }
    if (hasAnchor) buildAnchorCache();
    hasBackReferences = hasBackRef(source);
}

void RegExpSource::setSource(const std::u16string& s) {
    if (source == s) return;
    source = s;
    if (hasAnchor) buildAnchorCache();
}

void RegExpSource::buildAnchorCache() {
    std::u16string a0g0 = source, a0g1 = source, a1g0 = source, a1g1 = source;
    for (size_t pos = 0, len = source.size(); pos < len; ++pos) {
        if (source[pos] == u'\\' && pos + 1 < len) {
            char16_t n = source[pos + 1];
            if (n == u'A') {
                a0g0[pos + 1] = 0xFFFF; a0g1[pos + 1] = 0xFFFF; a1g0[pos + 1] = u'A'; a1g1[pos + 1] = u'A';
            } else if (n == u'G') {
                a0g0[pos + 1] = 0xFFFF; a0g1[pos + 1] = u'G'; a1g0[pos + 1] = 0xFFFF; a1g1[pos + 1] = u'G';
            }
            ++pos;
        }
    }
    cache_[0] = std::move(a0g0); cache_[1] = std::move(a0g1); cache_[2] = std::move(a1g0); cache_[3] = std::move(a1g1);
}

const std::u16string& RegExpSource::resolveAnchors(bool allowA, bool allowG) const {
    if (!hasAnchor) return source;
    return cache_[(allowA ? 2 : 0) + (allowG ? 1 : 0)];
}

std::u16string RegExpSource::resolveBackReferences(const char16_t* line, const std::vector<Cap>& caps) const {
    std::u16string out;
    out.reserve(source.size() + 16);
    size_t n = source.size();
    for (size_t i = 0; i < n;) {
        char16_t c = source[i];
        if (c == u'\\' && i + 1 < n && source[i + 1] >= u'0' && source[i + 1] <= u'9') {
            size_t j = i + 1;
            long idx = 0;
            while (j < n && source[j] >= u'0' && source[j] <= u'9') {
                if (idx < 100000) idx = idx * 10 + (source[j] - u'0');
                ++j;
            }
            if (idx >= 0 && idx < static_cast<long>(caps.size())) {
                const Cap& cp = caps[idx];
                for (int k = cp.start; k < cp.end; ++k) {
                    char16_t ch = line[k];
                    // escapeRegExpCharacters: /[\\|([{}\]).?*+^$]/
                    switch (ch) {
                        case u'\\': case u'|': case u'(': case u'[': case u'{': case u'}': case u']': case u')': case u'.': case u'?': case u'*': case u'+': case u'^': case u'$':
                            out.push_back(u'\\');
                            break;
                        default: break;
                    }
                    out.push_back(ch);
                }
            }
            i = j;
        } else {
            out.push_back(c);
            ++i;
        }
    }
    return out;
}

// ---------------------------------------------------------------- RegExpSourceList

void RegExpSourceList::unshift(RegExpSource* s) { items_.insert(items_.begin(), s); hasAnchors_ = hasAnchors_ || s->hasAnchor; }

void RegExpSourceList::unshiftOwned(std::unique_ptr<RegExpSource> s) {
    RegExpSource* p = s.get();
    owned_.push_back(std::move(s));
    unshift(p);
}

void RegExpSourceList::disposeCaches() {
    cached_.reset();
    for (auto& a : anchorCache_) a.reset();
}

void RegExpSourceList::setSource(size_t index, const std::u16string& newSource) {
    if (items_[index]->source != newSource) {
        disposeCaches();
        items_[index]->setSource(newSource);
    }
}

std::shared_ptr<Scanner> RegExpSourceList::build(RegexPool& pool, bool resolve, bool allowA, bool allowG) {
    std::vector<std::shared_ptr<RegexHolder>> regs;
    std::vector<int> ids;
    regs.reserve(items_.size());
    ids.reserve(items_.size());
    for (RegExpSource* s : items_) {
        regs.push_back(pool.get(resolve ? s->resolveAnchors(allowA, allowG) : s->source));
        ids.push_back(s->ruleId);
    }
    return std::make_shared<Scanner>(std::move(regs), std::move(ids));
}

std::shared_ptr<Scanner> RegExpSourceList::compile(RegexPool& pool) {
    if (!cached_) cached_ = build(pool, false, false, false);
    return cached_;
}

std::shared_ptr<Scanner> RegExpSourceList::compileAG(RegexPool& pool, bool allowA, bool allowG) {
    if (!hasAnchors_) return compile(pool);
    auto& slot = anchorCache_[(allowA ? 2 : 0) + (allowG ? 1 : 0)];
    if (!slot) slot = build(pool, true, allowA, allowG);
    return slot;
}

// ---------------------------------------------------------------- LineTokens

void LineTokens::produce(const AttrStack* scopes, int endIndex) {
    if (lastEnd_ >= endIndex) return;
    if (!emitScopes_) {
        int32_t meta = scopes ? static_cast<int32_t>(scopes->attrs) : 0;
        if (merge_ && !binary.empty() && binary.back() == meta) { lastEnd_ = endIndex; return; }
        binary.push_back(lastEnd_);
        binary.push_back(meta);
        lastEnd_ = endIndex;
        return;
    }
    tokens.push_back({lastEnd_, endIndex, Ref<AttrStack>(const_cast<AttrStack*>(scopes))});
    lastEnd_ = endIndex;
}

void LineTokens::finish(const AttrStack* contentScopes, int lineLength) {
    if (!emitScopes_) {
        if (!binary.empty() && binary[binary.size() - 2] == lineLength - 1) { binary.pop_back(); binary.pop_back(); }
        if (binary.empty()) {
            lastEnd_ = -1;
            produce(contentScopes, lineLength);
            binary[binary.size() - 2] = 0;
        }
        return;
    }
    if (!tokens.empty() && tokens.back().start == lineLength - 1) tokens.pop_back();
    if (tokens.empty()) {
        lastEnd_ = -1;
        produce(contentScopes, lineLength);
        tokens.back().start = 0;
    }
}

}  // namespace tmg
