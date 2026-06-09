#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# generate_keystore.sh — Run ONCE to create the production signing keystore.
# RFSAT Limited — ShimmerENACT / ENACT Horizon Europe Grant 101157151
#
# Usage:  chmod +x scripts/generate_keystore.sh && ./scripts/generate_keystore.sh
# Requires: Java keytool (included with any JDK)
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

KEYSTORE_FILE="rfsat_shimmerenact_release.jks"
KEY_ALIAS="shimmerenact"

echo "==================================================================="
echo "  ShimmerENACT Production Keystore Generator — RFSAT Limited"
echo "==================================================================="
echo "  Keep the keystore and passwords secret. Back them up."
echo "  Losing the keystore means you cannot update the Play Store app."
echo "==================================================================="
echo ""

read -s -p "Keystore password (min 8 chars): " STORE_PASS; echo
read -s -p "Confirm keystore password:        " STORE_PASS2; echo
[ "$STORE_PASS" = "$STORE_PASS2" ] || { echo "Mismatch."; exit 1; }

read -s -p "Key password (can be same):       " KEY_PASS; echo
read -s -p "Confirm key password:             " KEY_PASS2; echo
[ "$KEY_PASS" = "$KEY_PASS2" ] || { echo "Mismatch."; exit 1; }

echo ""
keytool -genkeypair \
  -keystore  "$KEYSTORE_FILE" \
  -alias     "$KEY_ALIAS" \
  -keyalg    RSA \
  -keysize   4096 \
  -validity  10000 \
  -storepass "$STORE_PASS" \
  -keypass   "$KEY_PASS" \
  -dname     "CN=RFSAT Limited, OU=ENACT, O=RFSAT Limited, L=Waterford, ST=Waterford, C=IE"

echo ""
echo "Keystore written to: $KEYSTORE_FILE"
echo ""

base64 -w 0 "$KEYSTORE_FILE" > keystore_base64.txt
echo "Base64 written to:   keystore_base64.txt"
echo ""
echo "==================================================================="
echo "  Add these four secrets to GitHub:"
echo "  Repo → Settings → Secrets and variables → Actions → New secret"
echo "==================================================================="
echo ""
echo "  KEYSTORE_BASE64  = contents of keystore_base64.txt"
echo "  KEY_ALIAS        = $KEY_ALIAS"
echo "  KEY_PASSWORD     = (key password entered above)"
echo "  STORE_PASSWORD   = (keystore password entered above)"
echo ""
echo "==================================================================="
echo "  For local release builds, add to ~/.gradle/gradle.properties:"
echo "==================================================================="
echo ""
echo "  KEYSTORE_PATH=$(realpath "$KEYSTORE_FILE")"
echo "  KEY_ALIAS=$KEY_ALIAS"
echo "  KEY_PASSWORD=<key password>"
echo "  STORE_PASSWORD=<keystore password>"
echo ""
echo "  Do NOT commit $KEYSTORE_FILE or keystore_base64.txt to git."
