#!/usr/bin/env bash
# Builds a host test binary: build_host.sh <out> <extra flags...> -- <main sources...>
# Needs libonig (dev headers via ONIG_INC, or the bundled copy) and g++ >= 9.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CPP="$HERE/../app/src/main/cpp"
OUT="$1"; shift
FLAGS=(); SRCS=()
while [ $# -gt 0 ]; do
  if [ "$1" = "--" ]; then shift; SRCS=("$@"); break; fi
  FLAGS+=("$1"); shift
done
ONIG_INC="${ONIG_INC:-/home/claude/work/onig-inc}"
ONIG_LIB="${ONIG_LIB:-/usr/lib/x86_64-linux-gnu/libonig.so.5}"
CORE=(tm_util tm_json tm_raw tm_theme tm_matcher tm_onig tm_registry tm_rules tm_grammar tm_tokenize tm_session)
FILES=()
for c in "${CORE[@]}"; do FILES+=("$CPP/$c.cpp"); done
g++ -std=c++17 -Wall -Wextra "${FLAGS[@]}" -I"$CPP" -I"$ONIG_INC" "${SRCS[@]}" "${FILES[@]}" -o "$OUT" "$ONIG_LIB"
