// Grammar/theme registry shared by sessions (thread-safe lookups; theme swapped atomically).
#pragma once
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "tm_raw.hpp"
#include "tm_theme.hpp"

namespace tmg {

class Registry {
public:
    /// Loads the bytes of a grammar file ("grammars/python.json"); returns false when unavailable.
    using Loader = std::function<bool(const std::string& file, std::string& bytes)>;

    explicit Registry(Loader loader);

    /// manifest.json: languages[].{scope,file,injectTo}. Files are requested as "grammars/<file>".
    bool loadManifest(const char* json, size_t n, std::string& err);

    /// Raw grammar for a scope name (parsed on first use, cached). nullptr if unknown/unparsable (see lastError()).
    std::shared_ptr<const RawGrammar> lookup(const std::string& scopeName);
    /// Scope names of grammars that inject into `scopeName`.
    std::vector<std::string> injectionsFor(const std::string& scopeName) const;

    /// Test/embedding hook: registers an already parsed grammar.
    void addGrammar(std::shared_ptr<const RawGrammar> g, std::vector<std::string> injectsInto = {});

    struct ThemeSnapshot {
        std::shared_ptr<const Theme> theme;
        uint32_t epoch = 0;
    };
    ThemeSnapshot themeSnapshot() const;
    /// Returns the ARGB colour table (index = colour id) or empty on error.
    std::vector<uint32_t> setTheme(const JValue& themeJson, uint32_t defaultFg, uint32_t defaultBg, std::string& err);

    std::string lastError() const;

private:
    Loader loader_;
    mutable std::mutex mu_;
    std::unordered_map<std::string, std::string> scopeToFile_;
    std::unordered_map<std::string, std::vector<std::string>> injections_;
    std::unordered_map<std::string, std::shared_ptr<const RawGrammar>> grammars_;
    std::unordered_map<std::string, bool> failed_;
    mutable std::mutex themeMu_;
    ThemeSnapshot theme_;
    std::string lastError_;
};

}  // namespace tmg
