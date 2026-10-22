#!/usr/bin/env bash
#
# Tests .githooks/commit-msg against messages it must accept and reject.
#   scripts/test-commit-msg.sh
#
# With COMMITLINT set to a commitlint executable (with @commitlint/config-conventional installed
# beside it), also checks that commitlint reaches the same verdict, so the hook can't drift from
# the CI rules:
#   COMMITLINT=/path/to/node_modules/.bin/commitlint scripts/test-commit-msg.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOOK="$ROOT/.githooks/commit-msg"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

passed=0
failed=0

# expect <pass|fail> <message> [description]
expect() {
    local want="$1" message="$2" description="${3:-$2}"
    printf '%s' "$message" > "$TMP/msg"
    local got output
    if output="$(cd "$ROOT" && "$HOOK" "$TMP/msg" 2>&1)"; then got=pass; else got=fail; fi
    if [ "$got" = "$want" ]; then
        passed=$((passed + 1))
    else
        failed=$((failed + 1))
        printf 'FAIL expected %s, got %s: %s\n%s\n\n' "$want" "$got" "$description" "$output"
    fi
    case "$description" in
        *hook-only*) return ;; # commitlint differs by design
    esac
    if [ -n "${COMMITLINT:-}" ]; then
        local lint lint_dir
        # commitlint resolves "extends" next to its config, so lint from the directory holding
        # node_modules (the repo root in CI), with this repo's config copied there if needed
        lint_dir="$(cd "$(dirname "$COMMITLINT")/../.." && pwd)"
        if ! cmp -s "$ROOT/.commitlintrc.json" "$lint_dir/.commitlintrc.json"; then
            cp "$ROOT/.commitlintrc.json" "$lint_dir/.commitlintrc.json"
        fi
        if (cd "$lint_dir" && "$COMMITLINT" < "$TMP/msg") >/dev/null 2>&1; then
            lint=pass
        else
            lint=fail
        fi
        if [ "$lint" != "$want" ]; then
            failed=$((failed + 1))
            printf 'FAIL commitlint says %s, expected %s: %s\n\n' "$lint" "$want" "$description"
        fi
    fi
}

long_subject="$(printf 'x%.0s' $(seq 1 94))" # "feat: " + 94 = 100
long_line="$(printf 'y%.0s' $(seq 1 101))"

# valid
expect pass "feat(candles): carry forward the last close"
expect pass "fix: resubscribe when a stream completes"
expect pass "feat(api)!: rename collect endpoint"
expect pass "perf(decimal): divide in 128 bits"
expect pass "docs: explain OHLC continuity" "acronym after a lower-case word"
expect pass "feat: add OHLC candles" "acronym later in the subject"
expect pass "feat: iOS support" "subject starting lower case"
expect pass "feat: 3 new candles" "subject starting with a digit"
expect pass "chore(weird-scope): tidy" "unknown scope only warns"
expect pass $'fix(config): rename key\n\nThe old name clashed.\n\nBREAKING CHANGE: mds.fetch-timeout is now mds.connection-timeout' "body and breaking footer"
expect pass $'feat: add thing\n\nRefs: #12\nBREAKING-CHANGE: removes the old thing' "hyphenated breaking footer"
expect pass $'fix: stop on the first error\n\nbreaking out of the loop early saves a query.' "prose starting with 'breaking' is not a breaking change"
expect pass $'feat(api)!: drop endpoint\n\nClients moved to the WebSocket.\n\nCo-Authored-By: A Person <a@example.com>\nBREAKING-CHANGE: GET /api/v1/stream is gone; use\n  /api/v1/ws/prices instead' "breaking footer as the last trailer, with a continuation line"
expect pass $'fix: handle null\n# Please enter the commit message\n# ------------------------ >8 ------------------------\ndiff --git a/x b/x\nThis Line Would Break Rules.' "comments and git commit -v diff are ignored"
expect pass $'feat: add thing\n\n\n' "trailing blank lines"
expect pass "Merge branch 'develop' into main" "git merge commit"
expect pass "Merge pull request #4 from scrisini/feat/socks-proxy" "GitHub merge commit"
expect pass 'Revert "feat: add thing"' "git revert"
expect pass "fixup! feat: add thing" "autosquash fixup"
expect pass "feat: $long_subject" "header of exactly 100 characters"

# invalid
expect fail "update stuff" "no type"
expect fail "Add daily, weekly, monthly and yearly OHLC candles" "this repo's old style"
expect fail "feature: add thing" "unknown type"
expect fail "Feat: add thing" "upper-case type"
expect fail "fix: Resubscribe on completion" "sentence-case subject"
expect fail "fix: resubscribe on completion." "trailing full stop"
expect fail "fix: " "empty subject"
expect fail "fix:missing space" "no space after colon"
expect fail "fix(): add thing" "empty scope (hook-only: commitlint ignores it)"
expect fail "fix(Streaming): add thing" "non-kebab scope"
expect fail "fix: ADD THING" "upper-case subject"
expect fail "feat: OHLC candles for every period" "subject starting with an acronym"
expect fail "feat: 3 NEW CANDLES" "upper-case subject starting with a digit (hook-only: stricter)"
expect fail "feat: x$long_subject" "header over 100 characters"
expect fail $'fix: add thing\n\n'"$long_line" "body line over 100 characters"
expect fail $'feat: drop endpoint\n\nbreaking change: the endpoint is gone' "lower-case breaking footer (hook-only: commitlint misses the misspelling)"
expect fail $'feat: drop endpoint\n\nBREAKING CHANGE the endpoint is gone' "breaking footer without colon (hook-only: commitlint misses the misspelling)"
expect fail $'feat: drop endpoint\n\nBREAKING CHANGES: the endpoint is gone' "plural breaking footer (hook-only: semantic-release would miss it)"
expect fail $'fix: keep old clients working\n\nBreaking change avoided by keeping the old field.' "prose starting with 'breaking change' (hook-only: semantic-release would cut a major)"
expect fail "" "empty message"

printf '%d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
