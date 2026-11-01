#include "tm_onig.hpp"

#include <climits>
#include <mutex>

#include "oniguruma.h"

namespace tmg {

namespace {

std::once_flag g_once;
int g_failures = 0;
std::string g_lastError;
std::mutex g_errMutex;

void initOnig() {
    OnigEncoding encs[1] = {ONIG_ENCODING_UTF16_LE};
    onig_initialize(encs, 1);
    onig_set_retry_limit_in_match(10000000);
    onig_set_retry_limit_in_search(20000000);
}

bool containsG(const std::u16string& s) {
    for (size_t i = 0; i + 1 < s.size(); ++i) {
        if (s[i] == u'\\') {
            if (s[i + 1] == u'G') return true;
            ++i;
        }
    }
    return false;
}

bool isHex(char16_t c) { return (c >= u'0' && c <= u'9') || (c >= u'a' && c <= u'f') || (c >= u'A' && c <= u'F'); }

/// Oniguruma reads `\xHH` / `\0oo` as raw *bytes* of the pattern encoding; in UTF-16 a single byte is an invalid
/// (too short) character (ONIGERR_TOO_SHORT_MULTI_BYTE_STRING) whereas in UTF-8 it is the code point. Rewrite them
/// to the code point form `\x{H}` so every grammar means the same thing as under vscode-oniguruma.
std::u16string adaptEscapes(const std::u16string& src) {
    bool need = false;
    for (size_t i = 0; i + 1 < src.size(); ++i)
        if (src[i] == u'\\') { if (src[i + 1] == u'x' || src[i + 1] == u'0') { need = true; break; } ++i; }
    if (!need) return src;
    std::u16string out;
    out.reserve(src.size() + 8);
    static const char* kHex = "0123456789ABCDEF";
    auto emit = [&](unsigned v) {
        out += u"\\x{";
        char tmp[8];
        int n = 0;
        do { tmp[n++] = kHex[v & 15]; v >>= 4; } while (v);
        while (n) out.push_back(static_cast<char16_t>(tmp[--n]));
        out.push_back(u'}');
    };
    for (size_t i = 0; i < src.size(); ++i) {
        char16_t c = src[i];
        if (c != u'\\' || i + 1 >= src.size()) { out.push_back(c); continue; }
        char16_t n = src[i + 1];
        if (n == u'x' && !(i + 2 < src.size() && src[i + 2] == u'{') && i + 2 < src.size() && isHex(src[i + 2])) {
            unsigned v = 0;
            size_t j = i + 2;
            int digits = 0;
            while (j < src.size() && digits < 2 && isHex(src[j])) { char16_t h = src[j]; v = v * 16 + (h <= u'9' ? h - u'0' : (h | 32) - u'a' + 10); ++j; ++digits; }
            emit(v);
            i = j - 1;
        } else if (n == u'0') {
            unsigned v = 0;
            size_t j = i + 2;
            int digits = 0;
            while (j < src.size() && digits < 2 && src[j] >= u'0' && src[j] <= u'7') { v = v * 8 + (src[j] - u'0'); ++j; ++digits; }
            emit(v);
            i = j - 1;
        } else {
            out.push_back(c);
            out.push_back(n);
            ++i;
        }
    }
    return out;
}

struct RegionHolder {
    OnigRegion* r;
    RegionHolder() : r(onig_region_new()) {}
    ~RegionHolder() { onig_region_free(r, 1); }
};

OnigRegion* threadRegion() {
    thread_local RegionHolder h;
    return h.r;
}

}  // namespace

RegexHolder::RegexHolder(std::u16string source) : source_(std::move(source)) { hasG_ = containsG(source_); }

RegexHolder::~RegexHolder() { if (reg_) onig_free(reg_); }

bool RegexHolder::ensureCompiled() {
    if (compiled_) return !failed_;
    compiled_ = true;
    std::call_once(g_once, initOnig);
    OnigErrorInfo ei;
    const std::u16string adapted = adaptEscapes(source_);
    const OnigUChar* p = reinterpret_cast<const OnigUChar*>(adapted.data());
    int r = onig_new(&reg_, p, p + adapted.size() * 2, ONIG_OPTION_CAPTURE_GROUP, ONIG_ENCODING_UTF16_LE, ONIG_SYNTAX_ONIGURUMA, &ei);
    if (r != ONIG_NORMAL) {
        OnigUChar buf[ONIG_MAX_ERROR_MESSAGE_LEN];
        onig_error_code_to_str(buf, r, &ei);
        std::lock_guard<std::mutex> lk(g_errMutex);
        ++g_failures;
        g_lastError = std::string(reinterpret_cast<char*>(buf));
        reg_ = nullptr;
        failed_ = true;
        return false;
    }
    return true;
}

int RegexHolder::search(const LineStr& s, int start, int limit, std::vector<Cap>& caps) {
    if (!ensureCompiled()) return -1;
    // The cache stores the true first match at/after cStart_ (or "none"), independent of `limit`.
    // Oniguruma's `range` is unreliable with its literal-search optimisations (a range that ends inside a literal
    // misses it), so every real search runs to the end of the line, exactly like vscode-oniguruma.
    if (!hasG_ && cSerial_ == s.serial && cStart_ <= start && (cPos_ < 0 || cPos_ >= start)) {
        if (cPos_ < 0 || cPos_ > limit) return -1;
        caps = cCaps_;
        return cPos_;
    }
    OnigRegion* region = threadRegion();
    const OnigUChar* b = reinterpret_cast<const OnigUChar*>(s.data);
    const OnigUChar* e = b + static_cast<size_t>(s.len) * 2;
    int r = onig_search(reg_, b, e, b + static_cast<size_t>(start) * 2, e, region, ONIG_OPTION_NONE);
    int pos = -1;
    if (r >= 0) {
        pos = r / 2;
        int n = region->num_regs;
        cCaps_.resize(n);
        for (int i = 0; i < n; ++i) {
            int bg = region->beg[i], en = region->end[i];
            if (bg < 0 || en < 0) cCaps_[i] = {0, 0};
            else cCaps_[i] = {bg / 2, en / 2};
        }
    } else {
        cCaps_.clear();
    }
    if (!hasG_) { cSerial_ = s.serial; cStart_ = start; cPos_ = pos; }
    if (pos < 0 || pos > limit) return -1;
    caps = cCaps_;
    return pos;
}

int Scanner::find(const LineStr& s, int start, int limit, std::vector<Cap>& caps) const {
    int bestIdx = -1, bestPos = INT_MAX;
    std::vector<Cap> tmp;
    for (size_t i = 0; i < regs_.size(); ++i) {
        int lim = bestIdx >= 0 ? bestPos - 1 : limit;
        if (lim < start) break;
        int pos = regs_[i]->search(s, start, lim, tmp);
        if (pos >= 0 && pos < bestPos) {
            bestPos = pos;
            bestIdx = static_cast<int>(i);
            caps.swap(tmp);
            if (pos == start) break;
        }
    }
    return bestIdx;
}

std::shared_ptr<RegexHolder> RegexPool::get(const std::u16string& source) {
    auto it = map_.find(source);
    if (it != map_.end()) return it->second;
    if (map_.size() > 20000) map_.clear();   // holders stay alive through the scanners that use them
    auto h = std::make_shared<RegexHolder>(source);
    map_.emplace(source, h);
    return h;
}

int onigCompileFailures() { std::lock_guard<std::mutex> lk(g_errMutex); return g_failures; }
std::string onigLastError() { std::lock_guard<std::mutex> lk(g_errMutex); return g_lastError; }
const char* onigVersionString() { return onig_version(); }

}  // namespace tmg
