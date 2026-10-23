#!/usr/bin/env bash
#
# Tests scripts/check-secrets.sh: stages files in a throwaway repository and checks which commits it
# would allow. Run after changing the script.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

git -C "$WORK" init -q
mkdir -p "$WORK/scripts"
cp "$ROOT/scripts/check-secrets.sh" "$WORK/scripts/"

passed=0
failed=0

# expect <pass|fail> <description> <path> <content>
expect() {
    local outcome="$1" description="$2" path="$3" content="$4" result
    mkdir -p "$WORK/$(dirname "$path")"
    printf '%s\n' "$content" > "$WORK/$path"
    git -C "$WORK" add -- "$path"
    if (cd "$WORK" && scripts/check-secrets.sh >/dev/null 2>&1); then result="pass"; else result="fail"; fi
    git -C "$WORK" rm -q --cached -- "$path"
    rm -f "$WORK/$path"
    if [ "$result" = "$outcome" ]; then
        passed=$((passed + 1))
    else
        failed=$((failed + 1))
        printf 'FAIL (%s expected): %s\n' "$outcome" "$description" >&2
    fi
}

ENCRYPTED_ENV='MDS_INFLUX_TOKEN=ENC[AES256_GCM,data:abc=,iv:def=,tag:ghi=,type:str]
sops_version=3.9.0'
ENCRYPTED_YAML='mds:
    influx:
        token: ENC[AES256_GCM,data:abc=,iv:def=,tag:ghi=,type:str]
sops:
    version: 3.9.0'

expect pass "ordinary code" src/Main.java 'class Main {}'
expect pass "an example env file" .env.example 'MDS_INFLUX_TOKEN='
expect pass "a SOPS-encrypted dotenv file" config/secrets/local.enc.env "$ENCRYPTED_ENV"
expect pass "a SOPS-encrypted YAML file" config/secrets/ci.enc.yaml "$ENCRYPTED_YAML"
expect pass "the secrets directory's README" config/secrets/README.md 'Encrypted files only.'
expect pass "a placeholder that only looks like a key id" README.md 'set AWS_ACCESS_KEY_ID=AKIA... in the environment'

expect fail "a plaintext file under config/secrets" config/secrets/local.enc.env 'MDS_INFLUX_TOKEN=real-token'
expect fail "encrypted values without SOPS metadata" config/secrets/x.enc.env 'A=ENC[AES256_GCM,data:abc=]'
expect fail "a .env file" .env 'MDS_INFLUX_TOKEN=real-token'
expect fail "a .env.local file" .env.local 'MDS_INFLUX_TOKEN=real-token'
expect fail "a PEM file" certs/server.pem 'anything'
expect fail "an SSH key file" id_ed25519 'anything'
expect fail "a decrypted SOPS file" config/secrets/local.dec.env 'MDS_INFLUX_TOKEN=real-token'
expect fail "a private key in a text file" notes.txt '-----BEGIN RSA PRIVATE KEY-----'
expect fail "a PKCS#8 private key" notes.txt '-----BEGIN PRIVATE KEY-----'
expect fail "an age identity" notes.txt 'AGE-SECRET-KEY-1QYQSZQGPQYQSZQGPQYQSZQGPQYQSZQGPQYQSZQGPQYQSZQGPQYQS'
expect fail "an AWS access key id" src/main/resources/application.yml 'key: AKIAIOSFODNN7EXAMPLE'
expect fail "a Vault token" src/test/resources/vault.txt 'token=hvs.CAESIJ1a2b3c4d5e6f7g8h9i0jklmn'

# --all checks tracked files rather than staged ones
printf 'MDS_INFLUX_TOKEN=real-token\n' > "$WORK/.env"
git -C "$WORK" add .env
git -C "$WORK" -c user.name=t -c user.email=t@example.com commit -q --no-verify -m "chore: test"
if (cd "$WORK" && scripts/check-secrets.sh --all >/dev/null 2>&1); then
    failed=$((failed + 1)); echo "FAIL (fail expected): --all finds a committed .env" >&2
else
    passed=$((passed + 1))
fi

printf '%d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
