#!/usr/bin/env bash
# Host build + tests of the native TextMate engine (see docs/NATIVE.md):
#   1. every regex of the 260 bundled grammars compiles with Oniguruma (UTF-16LE)
#   2. vscode-textmate tokenization corpus (first-mate + suite1 + while rules)         95 tests
#   3. vscode-textmate theme golden tests (14 themes x 72 files, token colours)
#   4. every bundled language tokenizes its sample through the production Session API; resume-from-checkpoint == full pass
#   5. the same under AddressSanitizer + UndefinedBehaviourSanitizer (corpus + smoke)
# Also builds the JNI library for the host (JVM tests load it through -Dcodeedit.native.lib).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
OUT=${NATIVE_BUILD_DIR:-$ROOT/native-tests/build}
mkdir -p "$OUT"
bash tools/ensure_assets.sh
bash tools/fetch_test_corpus.sh
GEN=(); command -v ninja >/dev/null 2>&1 && GEN=(-G Ninja)
JH=${JAVA_HOME:-}
[ -z "$JH" ] && command -v javac >/dev/null 2>&1 && JH=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
JNI=(); [ -n "$JH" ] && [ -f "$JH/include/jni.h" ] && JNI=(-DCODEEDIT_HOST_JNI=ON -DJAVA_HOME="$JH")

cmake -S app/src/main/cpp -B "$OUT/rel" "${GEN[@]}" -DCMAKE_BUILD_TYPE=Release -DCODEEDIT_HOST_TESTS=ON "${JNI[@]}"
cmake --build "$OUT/rel" -j"$(nproc)"

python3 native-tests/prepare_corpus.py test-corpus "$OUT/corpus"
python3 native-tests/prepare_themes.py test-corpus/themes "$OUT/themes"
A=app/src/main/assets/textmate
"$OUT/rel/check_regex" $A/grammars
"$OUT/rel/run_corpus" "$OUT/corpus"
"$OUT/rel/run_themes" "$OUT/themes"
"$OUT/rel/smoke_all" $A test-corpus/samples

if [ "${SKIP_SANITIZERS:-0}" != "1" ]; then
  cmake -S app/src/main/cpp -B "$OUT/asan" "${GEN[@]}" -DCMAKE_BUILD_TYPE=RelWithDebInfo -DCODEEDIT_HOST_TESTS=ON -DCODEEDIT_SANITIZE=ON
  cmake --build "$OUT/asan" -j"$(nproc)"
  ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 "$OUT/asan/run_corpus" "$OUT/corpus"
  ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 "$OUT/asan/smoke_all" $A test-corpus/samples
fi

if [ -f "$OUT/rel/libcodeedit_native.so" ]; then
  echo "HOST_JNI_LIB=$OUT/rel/libcodeedit_native.so"
  [ -n "${GITHUB_ENV:-}" ] && echo "CODEEDIT_HOST_LIB=$OUT/rel/libcodeedit_native.so" >> "$GITHUB_ENV"
fi
echo "NATIVE TESTS PASSED"
