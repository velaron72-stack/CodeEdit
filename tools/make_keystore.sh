#!/usr/bin/env bash
# Creates a PERMANENT release key and writes the four values for
#   GitHub -> repository -> Settings -> Secrets and variables -> Actions -> New repository secret
# Needs a JDK (keytool) and base64. Termux: pkg install openjdk-17
#
# Output (never commit, never share):  signing/release.jks  and  signing/github-secrets.txt
# KEEP signing/release.jks somewhere safe: with a different key Android refuses to install a new build over the old one.
set -eu
cd "$(dirname "$0")/.."
command -v keytool >/dev/null 2>&1 || { echo "keytool not found. Install a JDK (Termux: pkg install openjdk-17)."; exit 1; }
KS=signing/release.jks
OUT=signing/github-secrets.txt
[ -e "$KS" ] && { echo "$KS already exists; not overwriting. Move it away first."; exit 1; }
mkdir -p signing
umask 077
ALIAS=codeedit
PASS="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"        # PKCS12: store and key password are the same value
keytool -genkeypair -keystore "$KS" -storetype PKCS12 -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=Code Edit, O=Code Edit, C=UA" >/dev/null
{
  echo "KEYSTORE_BASE64=$(base64 < "$KS" | tr -d '\n')"
  echo "KEYSTORE_PASSWORD=$PASS"
  echo "KEY_ALIAS=$ALIAS"
  echo "KEY_PASSWORD=$PASS"
} > "$OUT"
echo "Key:    $KS"
echo "Values: $OUT   (4 lines NAME=value: create one GitHub secret per line, NAME = text before '=', value = text after)"
echo "After pasting the secrets delete $OUT; keep $KS as the backup of the key."
