#include "tm_json.hpp"

#include <cstdlib>
#include <cstring>

namespace tmg {

const JValue* JValue::get(const char* key) const {
    if (type != Obj) return nullptr;
    for (size_t i = obj.size(); i-- > 0;)
        if (obj[i].first == key) return &obj[i].second;
    return nullptr;
}

const std::string* JValue::getStr(const char* key) const {
    const JValue* v = get(key);
    return (v && v->type == Str) ? &v->str : nullptr;
}

namespace {

struct Parser {
    const char* p;
    const char* end;
    std::string err;
    int depth = 0;

    bool fail(const char* m) { if (err.empty()) err = m; return false; }

    void ws() {
        for (;;) {
            while (p < end && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')) ++p;
            if (p + 1 < end && p[0] == '/' && p[1] == '/') { while (p < end && *p != '\n') ++p; continue; }
            if (p + 1 < end && p[0] == '/' && p[1] == '*') {
                p += 2;
                while (p + 1 < end && !(p[0] == '*' && p[1] == '/')) ++p;
                p = (p + 1 < end) ? p + 2 : end;
                continue;
            }
            if (p < end && static_cast<unsigned char>(*p) == 0xEF && end - p >= 3 && static_cast<unsigned char>(p[1]) == 0xBB && static_cast<unsigned char>(p[2]) == 0xBF) { p += 3; continue; }
            break;
        }
    }

    static void putUtf8(std::string& s, unsigned cp) {
        if (cp < 0x80) s.push_back(static_cast<char>(cp));
        else if (cp < 0x800) { s.push_back(static_cast<char>(0xC0 | (cp >> 6))); s.push_back(static_cast<char>(0x80 | (cp & 0x3F))); }
        else if (cp < 0x10000) { s.push_back(static_cast<char>(0xE0 | (cp >> 12))); s.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F))); s.push_back(static_cast<char>(0x80 | (cp & 0x3F))); }
        else { s.push_back(static_cast<char>(0xF0 | (cp >> 18))); s.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F))); s.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F))); s.push_back(static_cast<char>(0x80 | (cp & 0x3F))); }
    }

    bool hex4(unsigned& out) {
        if (end - p < 4) return false;
        unsigned v = 0;
        for (int i = 0; i < 4; ++i) {
            char c = p[i];
            v <<= 4;
            if (c >= '0' && c <= '9') v |= c - '0';
            else if (c >= 'a' && c <= 'f') v |= c - 'a' + 10;
            else if (c >= 'A' && c <= 'F') v |= c - 'A' + 10;
            else return false;
        }
        p += 4;
        out = v;
        return true;
    }

    bool parseString(std::string& out) {
        // p at opening quote
        ++p;
        for (;;) {
            if (p >= end) return fail("unterminated string");
            // fast path: copy run of plain chars
            const char* q = p;
            while (q < end && *q != '"' && *q != '\\') ++q;
            out.append(p, q - p);
            p = q;
            if (p >= end) return fail("unterminated string");
            if (*p == '"') { ++p; return true; }
            ++p;  // backslash
            if (p >= end) return fail("bad escape");
            char e = *p++;
            switch (e) {
                case '"': out.push_back('"'); break;
                case '\\': out.push_back('\\'); break;
                case '/': out.push_back('/'); break;
                case 'b': out.push_back('\b'); break;
                case 'f': out.push_back('\f'); break;
                case 'n': out.push_back('\n'); break;
                case 'r': out.push_back('\r'); break;
                case 't': out.push_back('\t'); break;
                case 'u': {
                    unsigned u;
                    if (!hex4(u)) return fail("bad \\u escape");
                    if (u >= 0xD800 && u <= 0xDBFF && end - p >= 6 && p[0] == '\\' && p[1] == 'u') {
                        const char* save = p;
                        p += 2;
                        unsigned lo;
                        if (hex4(lo) && lo >= 0xDC00 && lo <= 0xDFFF) u = 0x10000 + ((u - 0xD800) << 10) + (lo - 0xDC00);
                        else p = save;
                    }
                    if (u >= 0xD800 && u <= 0xDFFF) u = 0xFFFD;
                    putUtf8(out, u);
                    break;
                }
                default: return fail("bad escape");
            }
        }
    }

    bool parseValue(JValue& v) {
        ws();
        if (p >= end) return fail("unexpected end");
        if (++depth > 512) return fail("nesting too deep");
        struct D { int& d; ~D() { --d; } } guard{depth};
        char c = *p;
        if (c == '{') {
            ++p;
            v.type = JValue::Obj;
            ws();
            if (p < end && *p == '}') { ++p; return true; }
            for (;;) {
                ws();
                if (p >= end) return fail("unterminated object");
                if (*p == '}') { ++p; return true; }  // trailing comma
                if (*p != '"') return fail("expected key");
                std::string key;
                if (!parseString(key)) return false;
                ws();
                if (p >= end || *p != ':') return fail("expected ':'");
                ++p;
                v.obj.emplace_back(std::move(key), JValue());
                if (!parseValue(v.obj.back().second)) return false;
                ws();
                if (p < end && *p == ',') { ++p; continue; }
                if (p < end && *p == '}') { ++p; return true; }
                return fail("expected ',' or '}'");
            }
        }
        if (c == '[') {
            ++p;
            v.type = JValue::Arr;
            ws();
            if (p < end && *p == ']') { ++p; return true; }
            for (;;) {
                ws();
                if (p >= end) return fail("unterminated array");
                if (*p == ']') { ++p; return true; }
                v.arr.emplace_back();
                if (!parseValue(v.arr.back())) return false;
                ws();
                if (p < end && *p == ',') { ++p; continue; }
                if (p < end && *p == ']') { ++p; return true; }
                return fail("expected ',' or ']'");
            }
        }
        if (c == '"') { v.type = JValue::Str; return parseString(v.str); }
        if (end - p >= 4 && !memcmp(p, "true", 4)) { p += 4; v.type = JValue::Bool; v.b = true; return true; }
        if (end - p >= 5 && !memcmp(p, "false", 5)) { p += 5; v.type = JValue::Bool; v.b = false; return true; }
        if (end - p >= 4 && !memcmp(p, "null", 4)) { p += 4; v.type = JValue::Null; return true; }
        if (c == '-' || (c >= '0' && c <= '9')) {
            char* e = nullptr;
            std::string tmp(p, end - p > 64 ? 64 : end - p);
            v.num = strtod(tmp.c_str(), &e);
            if (e == tmp.c_str()) return fail("bad number");
            p += (e - tmp.c_str());
            v.type = JValue::Num;
            return true;
        }
        return fail("unexpected character");
    }
};

}  // namespace

bool parseJson(const char* data, size_t n, JValue& out, std::string& err) {
    Parser ps{data, data + n, {}, 0};
    out = JValue();
    if (!ps.parseValue(out)) { err = ps.err; return false; }
    ps.ws();
    if (ps.p != ps.end) { err = "trailing characters"; return false; }
    return true;
}

}  // namespace tmg
