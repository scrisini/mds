#!/usr/bin/env bash
#
# Rejects secrets that would land in git in plaintext. Secrets that must live in the repository go,
# SOPS-encrypted, under config/secrets/ (README "Secrets in git (SOPS)"); everything else belongs in
# Vault or the environment.
#
#   scripts/check-secrets.sh          check the staged files (the pre-commit hook)
#   scripts/check-secrets.sh --all    check every tracked file (the secrets workflow)
#
# Rejects:
#   - files under config/secrets/ (other than README.md) that aren't SOPS-encrypted;
#   - key and plaintext secret files: .env, *.pem, *.key, *.p12, *.pfx, *.jks, SSH and age keys,
#     and decrypted SOPS files (*.dec.*);
#   - private keys, age identities, AWS access key ids and Vault tokens in any text file.
# A false alarm can be committed with `git commit --no-verify`; the workflow will still check.

set -euo pipefail

SECRETS_DIR="config/secrets/"
# these two describe the patterns, so they'd match themselves
EXEMPT_SELF="scripts/check-secrets.sh"
EXEMPT_TEST="scripts/test-check-secrets.sh"

mode="staged"
case "${1:-}" in
    --all) mode="all" ;;
    "") ;;
    *) echo "usage: $0 [--all]" >&2; exit 2 ;;
esac

cd "$(git rev-parse --show-toplevel)"
scratch="$(mktemp)"
trap 'rm -f "$scratch"' EXIT

list_files() {
    if [ "$mode" = "staged" ]; then
        git diff --cached --name-only --diff-filter=ACMR -z
    else
        git ls-files -z
    fi
}

# The content being committed: the staged version, or the working copy for --all
content_of() {
    if [ "$mode" = "staged" ]; then
        git show ":$1"
    else
        cat -- "$1"
    fi
}

problems=0
reject() {
    printf '  %s: %s\n' "$1" "$2" >&2
    problems=$((problems + 1))
}

has() { grep -qIE -- "$1" "$scratch"; }

while IFS= read -r -d '' path; do
    if [ "$path" = "$EXEMPT_SELF" ] || [ "$path" = "$EXEMPT_TEST" ]; then
        continue
    fi
    base="${path##*/}"
    case "$base" in
        .env.example|.env.sample) ;;
        .env|.env.*|*.pem|*.key|*.p12|*.pfx|*.jks|*.keystore|id_rsa*|id_ecdsa*|id_ed25519*|*.agekey)
            reject "$path" "a key or plaintext secrets file: keep it out of git, or SOPS-encrypt it under $SECRETS_DIR"
            continue ;;
        *.dec.*)
            reject "$path" "a decrypted SOPS file: commit the encrypted one only"
            continue ;;
    esac
    content_of "$path" > "$scratch" 2>/dev/null || continue
    case "$path" in
        "$SECRETS_DIR"README.md) ;;
        "$SECRETS_DIR"*)
            if ! has 'ENC\[AES256_GCM,' || ! has '^sops:|^sops_version=|"sops":'; then
                reject "$path" "not SOPS-encrypted: run 'sops -e -i $path'"
            fi
            continue ;;
    esac
    has '-----BEGIN ([A-Z]+ )*PRIVATE KEY-----' && reject "$path" "contains a private key"
    has 'AGE-SECRET-KEY-1' && reject "$path" "contains an age secret key"
    has '(^|[^A-Z0-9])(AKIA|ASIA)[A-Z0-9]{16}([^A-Z0-9]|$)' && reject "$path" "contains an AWS access key id"
    has '(^|[^A-Za-z0-9])hvs\.[A-Za-z0-9_-]{20,}' && reject "$path" "contains a Vault token"
done < <(list_files)

if [ "$problems" -gt 0 ]; then
    printf '\n%d plaintext secret(s) found. Put secrets in Vault or the environment, or SOPS-encrypt them\nunder %s (README "Secrets in git (SOPS)").\n' "$problems" "$SECRETS_DIR" >&2
    exit 1
fi
