#!/bin/bash
# Fills app/src/main/assets/{textmate,fonts} from upstream packages when they are missing.
# In a full source ZIP everything is already present and this script does nothing.
set -e
cd "$(dirname "$0")/.."
A=app/src/main/assets
if [ ! -f $A/textmate/manifest.json ] || [ "$(ls $A/textmate/grammars 2>/dev/null | wc -l)" -lt 200 ] || [ "$(ls $A/textmate/themes 2>/dev/null | wc -l)" -lt 60 ]; then
  echo "fetching tm-grammars / tm-themes from npm"
  T=$(mktemp -d); (cd $T && npm pack tm-grammars@1.32.22 tm-themes@1.12.12 >/dev/null && for f in *.tgz; do mkdir -p ${f%.tgz} && tar xzf $f -C ${f%.tgz}; done)
  mkdir -p $A/textmate/grammars $A/textmate/themes third_party_licenses
  cp $T/tm-grammars-*/package/grammars/*.json $A/textmate/grammars/
  cp $T/tm-themes-*/package/themes/*.json $A/textmate/themes/
  cp $T/tm-grammars-*/package/LICENSE third_party_licenses/tm-grammars-LICENSE.txt || true
  cp $T/tm-grammars-*/package/NOTICE third_party_licenses/tm-grammars-NOTICE.txt || true
  cp $T/tm-themes-*/package/LICENSE third_party_licenses/tm-themes-LICENSE.txt || true
  cp $T/tm-themes-*/package/NOTICE third_party_licenses/tm-themes-NOTICE.txt || true
  mkdir -p $T/pkgroot/tm-grammars $T/pkgroot/tm-themes
  cp -r $T/tm-grammars-*/package/. $T/pkgroot/tm-grammars/; cp -r $T/tm-themes-*/package/. $T/pkgroot/tm-themes/
  python3 tools/gen_manifest.py --pkg $T/pkgroot
fi
if [ ! -f $A/fonts/JetBrainsMono-Regular.ttf ]; then
  echo "fetching JetBrains Mono (OFL-1.1)"
  mkdir -p $A/fonts third_party_licenses; T=$(mktemp -d)
  curl -fsSL -o $T/jb.zip https://github.com/JetBrains/JetBrainsMono/releases/download/v2.304/JetBrainsMono-2.304.zip && \
    unzip -q -o $T/jb.zip 'fonts/ttf/JetBrainsMono-Regular.ttf' 'fonts/ttf/JetBrainsMono-Bold.ttf' 'OFL.txt' -d $T && \
    cp $T/fonts/ttf/JetBrainsMono-Regular.ttf $T/fonts/ttf/JetBrainsMono-Bold.ttf $A/fonts/ && cp $T/OFL.txt third_party_licenses/JetBrainsMono-OFL.txt || echo "WARN: font download failed; app falls back to system monospace"
fi
echo "assets ok: $(ls $A/textmate/grammars | wc -l) grammars, $(ls $A/textmate/themes | wc -l) themes"
