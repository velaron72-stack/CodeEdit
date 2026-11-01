// One tokenization session = one Grammar instance + checkpointed states (every `stride` lines).
#pragma once
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "tm_grammar.hpp"

namespace tmg {

class Session {
public:
    Session(std::shared_ptr<Registry> registry, std::shared_ptr<const RawGrammar> root, int maxLineChars, int stride, int maxCheckpoints);

    bool prepare(std::string& err);

    /// Drops checkpoints stored at lines > line (line < 0: all).
    void invalidateFrom(int line);
    /// Largest line L <= line having a stored state; 0 is always valid.
    int nearestCheckpoint(int line) const;
    int maxCheckpoint() const;
    int checkpointCount() const { return static_cast<int>(checkpoints_.size()); }

    /// See ARCHITECTURE.md (JNI contract). Returns false when no start state exists (and !cold).
    /// `offsets` has lineCount+1 entries into `chars`. Output: per emitted line [n, start0, meta0, ...].
    bool tokenize(int startLine, const char16_t* chars, int charCount, const int* offsets, int lineCount, int emitFrom, bool cold, bool record,
                  int timeLimitMs, std::vector<int32_t>& out, std::string& err);

    Grammar& grammar() { return grammar_; }
    int stride() const { return stride_; }

private:
    void store(int line, const Ref<StateStack>& st);

    std::shared_ptr<Registry> registry_;
    Grammar grammar_;
    int maxLineChars_;
    int stride_;
    int maxCheckpoints_;
    std::map<int, Ref<StateStack>> checkpoints_;
};

}  // namespace tmg
