#include "tm_session.hpp"

namespace tmg {

Session::Session(std::shared_ptr<Registry> registry, std::shared_ptr<const RawGrammar> root, int maxLineChars, int stride, int maxCheckpoints)
    : registry_(registry),
      grammar_(*registry, std::move(root)),
      maxLineChars_(maxLineChars > 0 ? maxLineChars : 1 << 30),
      stride_(stride > 0 ? stride : 64),
      maxCheckpoints_(maxCheckpoints > 0 ? maxCheckpoints : 1 << 30) {}

bool Session::prepare(std::string& err) {
    grammar_.useTheme(registry_->themeSnapshot());
    return grammar_.prepare(err);
}

void Session::invalidateFrom(int line) {
    if (line < 0) { checkpoints_.clear(); return; }
    checkpoints_.erase(checkpoints_.upper_bound(line), checkpoints_.end());
}

int Session::nearestCheckpoint(int line) const {
    if (line < 0) return 0;
    auto it = checkpoints_.upper_bound(line);
    if (it == checkpoints_.begin()) return 0;
    --it;
    return it->first;
}

int Session::maxCheckpoint() const { return checkpoints_.empty() ? 0 : checkpoints_.rbegin()->first; }

void Session::store(int line, const Ref<StateStack>& st) {
    checkpoints_[line] = st;
    if (static_cast<int>(checkpoints_.size()) > maxCheckpoints_) {
        stride_ *= 2;
        for (auto it = checkpoints_.begin(); it != checkpoints_.end();) {
            if (it->first % stride_ != 0) it = checkpoints_.erase(it); else ++it;
        }
    }
}

bool Session::tokenize(int startLine, const char16_t* chars, int charCount, const int* offsets, int lineCount, int emitFrom, bool cold, bool record,
                       int timeLimitMs, std::vector<int32_t>& out, std::string& err) {
    Registry::ThemeSnapshot snap = registry_->themeSnapshot();
    if (snap.epoch != grammar_.themeEpoch()) {
        grammar_.useTheme(snap);
        checkpoints_.clear();   // colours are baked into the states
    }
    grammar_.initialState();   // also refreshes defaultMetadata()
    Ref<StateStack> state;
    bool approximate = false;
    if (startLine > 0) {
        auto it = checkpoints_.find(startLine);
        if (it != checkpoints_.end()) state = it->second;
        else if (cold) { approximate = true; record = false; }
        else { err = "no checkpoint at start line"; return false; }
    }
    for (int i = 0; i < lineCount; ++i) {
        const int line = startLine + i;
        if (offsets[i] < 0 || offsets[i + 1] < offsets[i] || offsets[i + 1] > charCount) { err = "bad offsets"; return false; }
        if (record && !approximate && i > 0 && line % stride_ == 0 && state) store(line, state);
        const char16_t* text = chars + offsets[i];
        const int len = offsets[i + 1] - offsets[i];
        if (len > maxLineChars_) {
            if (line >= emitFrom) { out.push_back(1); out.push_back(0); out.push_back(static_cast<int32_t>(grammar_.defaultMetadata())); }
            continue;
        }
        LineTokens lt(false, !lineContainsRtl(text, len));
        TokenizeResult r = grammar_.tokenizeLine(text, len, state, startLine > 0 && i == 0 && !state, lt, timeLimitMs);
        state = r.state;
        if (r.stoppedEarly) { approximate = true; record = false; }
        if (line >= emitFrom) {
            out.push_back(static_cast<int32_t>(lt.binary.size() / 2));
            out.insert(out.end(), lt.binary.begin(), lt.binary.end());
        }
    }
    const int endLine = startLine + lineCount;
    if (record && !approximate && lineCount > 0 && endLine % stride_ == 0 && state) store(endLine, state);
    return true;
}

}  // namespace tmg
