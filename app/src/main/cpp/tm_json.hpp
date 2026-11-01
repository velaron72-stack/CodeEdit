// Minimal, lenient JSON DOM (comments and trailing commas tolerated; duplicate keys: last wins on lookup).
#pragma once
#include <string>
#include <utility>
#include <vector>

namespace tmg {

struct JValue {
    enum Type : unsigned char { Null, Bool, Num, Str, Arr, Obj };
    Type type = Null;
    bool b = false;
    double num = 0;
    std::string str;
    std::vector<JValue> arr;
    std::vector<std::pair<std::string, JValue>> obj;

    bool isObj() const { return type == Obj; }
    bool isArr() const { return type == Arr; }
    bool isStr() const { return type == Str; }
    bool isNum() const { return type == Num; }
    bool isBool() const { return type == Bool; }
    /// Last member with this key or nullptr (linear scan: use for small objects).
    const JValue* get(const char* key) const;
    const std::string* getStr(const char* key) const;
};

/// Parses `n` bytes of UTF-8 JSON. On failure returns false and fills `err`.
bool parseJson(const char* data, size_t n, JValue& out, std::string& err);

}  // namespace tmg
