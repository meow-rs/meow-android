#!/usr/bin/env bash
# Signs an aligned, unsigned APK with Meow's release key, and refuses the
# result unless it carries the release certificate.
#
# The key is the `release` environment's secrets (RELEASE_KEYSTORE_BASE64,
# RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_PASSWORD), which only `main` and
# `v*` tags can read. The jobs that run this are handed the
# built APK as an artifact, so the Gradle and cargo build scripts never share
# a runner with the key.
#
# usage: sign-apk.sh <aligned.apk> <out.apk>
set -euo pipefail

# CN=meow-android, O=gengjiawen; valid until 2036-10-02. Releases up to
# v1.0.8 carry madeye's certificate (5540c1f2...) instead: an APK signed here
# cannot update one of those in place.
RELEASE_CERT_SHA256=21ebc43a73b2fc147bbadb77ed35cd58cf0e23aa3a559c5c183ad04722e22eff
# Not a secret: as one, Actions would mask "meow" on every line of the log.
RELEASE_KEY_ALIAS=meow

in=$1
out=$2
: "${RELEASE_KEYSTORE_BASE64:?no release key: run in the release environment}"

bt=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
ks=$(mktemp -p "${RUNNER_TEMP:-/tmp}")
trap 'rm -f "$ks"' EXIT
base64 -d <<<"$RELEASE_KEYSTORE_BASE64" >"$ks"

# No v4 `.idsig` beside the APK: that is only for `adb install --incremental`.
"$bt/apksigner" sign --ks "$ks" --ks-pass env:RELEASE_KEYSTORE_PASSWORD \
  --ks-key-alias "$RELEASE_KEY_ALIAS" --key-pass env:RELEASE_KEY_PASSWORD \
  --v4-signing-enabled false --out "$out" "$in"

# Every signer's certificate, whatever the line calls the signer: build-tools
# 36 prints "Signer #1 certificate ...", 37 "V3.0 Signer: certificate ...".
certs=$("$bt/apksigner" verify --print-certs "$out")
signed_with=$(sed -n 's/.*certificate SHA-256 digest: //p' <<<"$certs" | sort -u)
if [ "$signed_with" != "$RELEASE_CERT_SHA256" ]; then
  rm -f "$out"
  echo "$certs"
  echo "::error::signed with ${signed_with:-no certificate}, not the release certificate $RELEASE_CERT_SHA256"
  exit 1
fi
ls -l "$out"
