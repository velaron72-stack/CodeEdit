#!/usr/bin/env bash
# Fills test-corpus/{first-mate,suite1,themes,samples} when missing (a source ZIP already contains them).
#   first-mate, suite1, themes : vscode-textmate test-cases (MIT)           https://github.com/microsoft/vscode-textmate
#   samples                    : one sample file per bundled language       https://github.com/shikijs/textmate-grammars-themes
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p test-corpus
if [ ! -f test-corpus/first-mate/tests.json ] || [ ! -f test-corpus/suite1/tests.json ] || [ ! -f test-corpus/themes/grammars.json ]; then
  T=$(mktemp -d)
  git clone -q --depth 1 https://github.com/microsoft/vscode-textmate.git "$T/vt"
  for d in first-mate suite1 themes; do rm -rf "test-corpus/$d"; cp -r "$T/vt/test-cases/$d" "test-corpus/$d"; done
  rm -rf "$T"
fi
if [ ! -d test-corpus/samples ] || [ "$(ls test-corpus/samples | wc -l)" -lt 200 ]; then
  T=$(mktemp -d)
  git clone -q --depth 1 https://github.com/shikijs/textmate-grammars-themes.git "$T/tg"
  rm -rf test-corpus/samples; cp -r "$T/tg/samples" test-corpus/samples
  rm -rf "$T"
fi
echo "test corpus ok: $(ls test-corpus/samples | wc -l) samples"
