// Small shared helpers for the TextMate engine: UTF conversion, intrusive refcounting.
#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace tmg {

std::u16string toU16(const char* s, size_t n);
inline std::u16string toU16(const std::string& s) { return toU16(s.data(), s.size()); }
std::string toU8(const char16_t* s, size_t n);
inline std::string toU8(const std::u16string& s) { return toU8(s.data(), s.size()); }

/// Non-atomic intrusive reference counting (sessions/grammars are single-threaded by contract).
/// Chains (parent links) are released iteratively so very deep stacks cannot overflow the C++ stack.
struct RcNode {
    uint32_t rc = 0;
};

template <class T>
class Ref {
public:
    Ref() = default;
    Ref(std::nullptr_t) {}
    explicit Ref(T* p) : p_(p) { if (p_) ++p_->rc; }
    Ref(const Ref& o) : p_(o.p_) { if (p_) ++p_->rc; }
    Ref(Ref&& o) noexcept : p_(o.p_) { o.p_ = nullptr; }
    ~Ref() { reset(); }
    Ref& operator=(const Ref& o) { if (o.p_) ++o.p_->rc; T* old = p_; p_ = o.p_; release(old); return *this; }
    Ref& operator=(Ref&& o) noexcept { if (this != &o) { T* old = p_; p_ = o.p_; o.p_ = nullptr; release(old); } return *this; }
    void reset() { T* old = p_; p_ = nullptr; release(old); }
    T* get() const { return p_; }
    T* operator->() const { return p_; }
    T& operator*() const { return *p_; }
    explicit operator bool() const { return p_ != nullptr; }
    bool operator==(const Ref& o) const { return p_ == o.p_; }
    bool operator!=(const Ref& o) const { return p_ != o.p_; }
    /// Gives up ownership without decrementing (used by iterative release).
    T* detach() { T* t = p_; p_ = nullptr; return t; }

private:
    static void release(T* n) {
        while (n && --n->rc == 0) {
            T* next = n->parent.detach();   // T must expose `Ref<T> parent`
            delete n;
            n = next;
        }
    }
    T* p_ = nullptr;
};

template <class T, class... A>
Ref<T> makeRef(A&&... a) { return Ref<T>(new T(std::forward<A>(a)...)); }

}  // namespace tmg
