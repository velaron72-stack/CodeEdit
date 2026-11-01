// Oniguruma wrapper: UTF-16LE regexes, "scanner" = ordered set of regexes searched for the leftmost match
// (ties -> lowest index), same observable behaviour as vscode-oniguruma's OnigScanner.
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

struct re_pattern_buffer;   // OnigRegexType

namespace tmg {

struct Cap {
    int start;
    int end;
};

/// A line being tokenized. `serial` is unique per (data,len) so per-regex search caches can be validated.
struct LineStr {
    const char16_t* data;
    int len;
    uint64_t serial;
};

/// One compiled regex plus a tiny search cache (see Scanner::find). Shared between scanners of a grammar.
class RegexHolder {
public:
    explicit RegexHolder(std::u16string source);
    ~RegexHolder();
    RegexHolder(const RegexHolder&) = delete;
    RegexHolder& operator=(const RegexHolder&) = delete;

    const std::u16string& source() const { return source_; }
    bool failed() const { return failed_; }
    /// Compiles now (normally lazy); false when Oniguruma rejects the pattern.
    bool valid() { return ensureCompiled(); }
    /// First match start in [start, limit] (inclusive), or -1. Captures go to `caps` when found.
    int search(const LineStr& s, int start, int limit, std::vector<Cap>& caps);

private:
    bool ensureCompiled();
    std::u16string source_;
    bool hasG_ = false;
    bool compiled_ = false;
    bool failed_ = false;
    re_pattern_buffer* reg_ = nullptr;
    // cache of the last search
    uint64_t cSerial_ = 0;
    int cStart_ = 0, cPos_ = -1;
    std::vector<Cap> cCaps_;
};

class Scanner {
public:
    Scanner(std::vector<std::shared_ptr<RegexHolder>> regs, std::vector<int> ruleIds)
        : regs_(std::move(regs)), ruleIds_(std::move(ruleIds)) {}
    /// Leftmost match starting in [start, limit]; returns index of the winning regex or -1.
    int find(const LineStr& s, int start, int limit, std::vector<Cap>& caps) const;
    int ruleIdAt(int index) const { return ruleIds_[index]; }
    size_t size() const { return regs_.size(); }

private:
    std::vector<std::shared_ptr<RegexHolder>> regs_;
    std::vector<int> ruleIds_;
};

/// Per-grammar pool: identical sources share one compiled regex.
class RegexPool {
public:
    std::shared_ptr<RegexHolder> get(const std::u16string& source);
private:
    std::unordered_map<std::u16string, std::shared_ptr<RegexHolder>> map_;
};

/// Number of regexes Oniguruma refused to compile since process start (diagnostics).
int onigCompileFailures();
std::string onigLastError();
const char* onigVersionString();

}  // namespace tmg
