#!/usr/bin/env bash
#
# Enforces the branching policy in README "Branching and merging" with GitHub rulesets.
#
#   scripts/github-branch-rules.sh                      dry run: print the settings and rulesets
#   scripts/github-branch-rules.sh --apply              apply them to the GitHub repository
#   scripts/github-branch-rules.sh --apply --create-develop   also create develop from main
#
# What it sets:
#   - repository: squash commits are titled with the PR title and keep the commit messages in the
#     body (so BREAKING CHANGE footers still reach semantic-release); rebase merging is disabled
#   - main:    pull requests only, squash merge only, linear history, no force-push or deletion,
#              commitlint check must pass
#   - develop: pull requests only, merge commits only (no squash or rebase), no force-push or
#              deletion, commitlint check must pass
#
# Re-running updates the existing rulesets (matched by name). Requires the GitHub CLI (gh),
# authenticated with admin rights on the repository.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

apply=false
create_develop=false
for arg in "$@"; do
    case "$arg" in
        --apply) apply=true ;;
        --create-develop) create_develop=true ;;
        -h|--help) sed -n '3,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $arg" >&2; exit 1 ;;
    esac
done

command -v gh >/dev/null 2>&1 || { echo "error: the GitHub CLI (gh) is required" >&2; exit 1; }
repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"

# Name of the status check produced by .github/workflows/commitlint.yml (its job name)
CHECK="commitlint"

repository_settings='{
  "allow_squash_merge": true,
  "allow_merge_commit": true,
  "allow_rebase_merge": false,
  "squash_merge_commit_title": "PR_TITLE",
  "squash_merge_commit_message": "COMMIT_MESSAGES"
}'

# ruleset <name> <branch> <merge method>
ruleset() {
    local name="$1" branch="$2" method="$3" linear=""
    if [ "$method" = "squash" ]; then
        linear='{ "type": "required_linear_history" },'
    fi
    cat <<EOF
{
  "name": "$name",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["refs/heads/$branch"], "exclude": [] } },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    $linear
    {
      "type": "pull_request",
      "parameters": {
        "allowed_merge_methods": ["$method"],
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false
      }
    },
    {
      "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": false,
        "required_status_checks": [{ "context": "$CHECK" }]
      }
    }
  ]
}
EOF
}

main_rules="$(ruleset "main: squash merge only" main squash)"
develop_rules="$(ruleset "develop: merge commits only" develop merge)"

# id of the ruleset with this name, or empty
ruleset_id() {
    gh api "repos/$repo/rulesets" --jq ".[] | select(.name == \"$1\") | .id"
}

upsert_ruleset() {
    local body="$1" name id
    name="$(printf '%s' "$body" | sed -n 's/^  "name": "\(.*\)",$/\1/p')"
    id="$(ruleset_id "$name")"
    if [ -n "$id" ]; then
        printf '%s' "$body" | gh api -X PUT "repos/$repo/rulesets/$id" --input - >/dev/null
        echo "updated ruleset '$name' ($id)"
    else
        id="$(printf '%s' "$body" | gh api -X POST "repos/$repo/rulesets" --input - --jq .id)"
        echo "created ruleset '$name' ($id)"
    fi
}

if ! $apply; then
    echo "Dry run for $repo (nothing is changed; pass --apply to apply)."
    echo
    echo "Repository settings:"; echo "$repository_settings"; echo
    echo "Ruleset:"; echo "$main_rules"; echo
    echo "Ruleset:"; echo "$develop_rules"
    if $create_develop; then
        echo; echo "Would create branch develop from main if it doesn't exist."
    fi
    exit 0
fi

echo "Applying branch policy to $repo"
printf '%s' "$repository_settings" | gh api -X PATCH "repos/$repo" --input - >/dev/null
echo "updated repository merge settings"

if $create_develop; then
    if gh api "repos/$repo/branches/develop" >/dev/null 2>&1; then
        echo "branch develop already exists"
    else
        sha="$(gh api "repos/$repo/git/ref/heads/main" --jq .object.sha)"
        gh api -X POST "repos/$repo/git/refs" -f ref=refs/heads/develop -f sha="$sha" >/dev/null
        echo "created branch develop at ${sha:0:7}"
    fi
fi

upsert_ruleset "$main_rules"
upsert_ruleset "$develop_rules"
