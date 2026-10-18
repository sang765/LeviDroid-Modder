#!/usr/bin/env bash
# Reports the sync PR / conflict issue the "Sync upstream into dev" workflow
# opened for the commit that was just pushed. Called from `git push` through
# ~/.config/fish/functions/git.fish; a silent no-op outside this repo or off dev.
set -uo pipefail

REPO="sang765/LeviDroid-Modder"
WORKFLOW="Sync upstream into dev"
SYNC_BRANCH="sync/upstream-main"
CONFLICT_TITLE="Upstream sync blocked"

command -v gh >/dev/null 2>&1 || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0
case "$(git remote get-url origin 2>/dev/null)" in *LeviDroid-Modder*) ;; *) exit 0 ;; esac
[ "$(git rev-parse --abbrev-ref HEAD)" = "dev" ] || exit 0

SHA=$(git rev-parse HEAD)
echo "sync: waiting for the '${WORKFLOW}' run of ${SHA:0:7}…"

# The run is indexed a little after the push, hence the retries.
RUN=""
for _ in $(seq 1 30); do
  RUN=$(gh api "repos/${REPO}/actions/runs?head_sha=${SHA}" \
      --jq ".workflow_runs[] | select(.name==\"${WORKFLOW}\") | .id" 2>/dev/null | head -1)
  [ -n "${RUN}" ] && break
  sleep 10
done
if [ -z "${RUN}" ]; then
  echo "sync: run not indexed yet — https://github.com/${REPO}/actions"
  exit 0
fi

CONCLUSION=failure
if timeout 900 gh run watch "${RUN}" -R "${REPO}" --exit-status >/dev/null 2>&1; then
  CONCLUSION=success
fi

PR=$(gh pr list -R "${REPO}" --base dev --head "${SYNC_BRANCH}" --state open \
    --json number,title,url \
    --jq '.[0] | select(.) | "#\(.number) \(.title) \(.url)"' 2>/dev/null)
ISSUE=$(gh issue list -R "${REPO}" --state open --search "in:title \"${CONFLICT_TITLE}\"" \
    --json number,title,url \
    --jq '.[0] | select(.) | "#\(.number) \(.title) \(.url)"' 2>/dev/null)

echo "sync ${CONCLUSION}: https://github.com/${REPO}/actions/runs/${RUN}"
echo "sync PR:        ${PR:-none}"
echo "conflict issue: ${ISSUE:-none}"
