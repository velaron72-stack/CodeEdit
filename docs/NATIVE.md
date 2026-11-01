# Native TextMate engine

`app/src/main/cpp` — C++17 port of **vscode-textmate 9.3.2** on **Oniguruma 6.9.10** (UTF-16LE regex encoding, so Java `char`
indices are match offsets). Library `libcodeedit_native.so`, Kotlin side `highlight/TmNative.kt`, contract in `ARCHITECTURE.md`.

Ported: grammar compile (repository, `include`/`$self`/`$base`, external grammars), `tokenizeString` with `begin/end/while`,
`\G` / `\A` anchors, back-references in `end`/`while`, injections (`injectTo`, `injections`, `L:`/`R:` priorities), capture
retokenization, `applyEndPatternLast`, theme trie and `EncodedTokenAttributes`. Line states are checkpointed every N lines so a
viewport far inside a large file does not need a full pass.

## Verification that exists (run on a dev host, not on a device)
`tools/run_native_tests.sh` (also the first CI step) builds the engine for the host and runs:

| Check | Result when last run |
|---|---|
| `run_corpus` — token streams vs. reference vscode-textmate output on `test-corpus/` | 95 / 95 identical |
| `run_themes` — theme resolution vs. reference | 1008 / 1008 identical |
| `smoke_all` — every bundled grammar compiles and tokenizes a sample | 238 languages (the rest are helper grammars without a root scope) |
| ASan + UBSan build of the above | clean |

The reference outputs come from the original `vscode-textmate` package (see `native-tests/prepare_corpus.py`).
Throughput measured on the dev host: roughly 7–12 k lines/s per core (not measured on a phone).

## Build
Gradle's CMake step builds `arm64-v8a` and `armeabi-v7a`. Oniguruma is vendored under `cpp/third_party/oniguruma`; when that folder
is absent CMake fetches tag `v6.9.10`.
