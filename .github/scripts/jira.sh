#!/usr/bin/env bash
# Jira REST helpers, sourced by the workflow steps.
#
# v2 rather than v3 on purpose: v3 returns descriptions as ADF JSON, which is
# unreadable pasted into a PR. v2 hands back plain text we can use as-is.
#
# Requires: JIRA_BASE_URL, JIRA_USER_EMAIL, JIRA_API_TOKEN
# (no `set -e` here — GitHub Actions already runs steps with `bash -eo pipefail`)

_jira() {
  curl -sS --fail-with-body \
    -u "${JIRA_USER_EMAIL}:${JIRA_API_TOKEN}" \
    -H "Accept: application/json" \
    "$@"
}

# jira_get <KEY> — issue JSON on stdout
jira_get() {
  _jira "${JIRA_BASE_URL}/rest/api/2/issue/$1?fields=summary,description,issuetype,assignee,status,parent"
}

# jira_transition <KEY> <TARGET>
#
# TARGET is a destination status id ("10002") or its name ("검토 중"). Prefer the
# id: renaming a board column changes the name but never the id.
#
# The *transition* id is always looked up, never hardcoded — those change
# whenever someone edits the Jira workflow, and a stale one fails in a way
# nobody notices for weeks.
#
# A missing transition warns instead of failing: reshaping the Jira workflow
# should not start breaking every PR in the repo.
jira_transition() {
  # Not `status`: that name is a read-only parameter in zsh, and this file is
  # meant to be sourceable in a local shell for debugging, not just in Actions.
  local key="$1" target="$2" cur cur_id cur_name id

  cur=$(jira_get "$key" | jq -c '.fields.status')
  cur_id=$(printf '%s' "$cur" | jq -r '.id')
  cur_name=$(printf '%s' "$cur" | jq -r '.name')

  if [ "$cur_id" = "$target" ] || [ "$cur_name" = "$target" ]; then
    echo "::notice::${key} is already '${cur_name}'"
    return 0
  fi

  # The destination name comes back with the id so the log reads
  # "검토 중" rather than "10002" when callers pass an id.
  local match
  match=$(_jira "${JIRA_BASE_URL}/rest/api/2/issue/${key}/transitions" \
          | jq -r --arg t "$target" \
            'first(.transitions[] | select(.to.id == $t or .to.name == $t)
             | "\(.id) \(.to.name)") // empty')

  if [ -z "$match" ]; then
    echo "::warning::${key}: '${cur_name}' 에서 '${target}' 로 가는 전환이 없습니다. Jira 워크플로우를 확인하세요."
    return 0
  fi

  id=${match%% *}
  local to_name=${match#* }

  _jira -X POST -H "Content-Type: application/json" \
    -d "$(jq -nc --arg id "$id" '{transition: {id: $id}}')" \
    "${JIRA_BASE_URL}/rest/api/2/issue/${key}/transitions" >/dev/null

  echo "::notice::${key}: ${cur_name} → ${to_name}"
}
