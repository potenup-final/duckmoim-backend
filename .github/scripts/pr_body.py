#!/usr/bin/env python3
"""Rebuild the automated half of a pull request body.

Two marker-delimited blocks are refreshed on every push: the Jira context, so a
reviewer reads the requirement without leaving GitHub, and a task list derived
from the commits. Anything outside the markers was written by a person and is
never touched.

Reads one JSON object on stdin:
    {"issue": <Jira issue>, "commits": [...], "body": "...", "issue_number": 12 | null}
Writes the new body on stdout.
"""

import json
import os
import re
import sys

JIRA_MARKERS = ("<!-- jira:start -->", "<!-- jira:end -->")
TASK_MARKERS = ("<!-- tasks:start -->", "<!-- tasks:end -->")

# Jira descriptions can run long. Past this, the block is collapsed so the
# human-written part of the PR stays visible without scrolling past a spec.
COLLAPSE_OVER = 1200


def splice(body, markers, block):
    """Replace the marker region, or prepend the block when the markers aren't there yet."""
    start, end = markers
    region = re.compile(re.escape(start) + ".*?" + re.escape(end), re.DOTALL)
    new = "{}\n{}\n{}".format(start, block, end)
    if region.search(body):
        # A lambda, not a plain string: backslashes in Jira text would otherwise
        # be read as group references and blow up the substitution.
        return region.sub(lambda _: new, body, count=1)
    return new + "\n\n" + body


def strip_markers(text):
    """Keep a Jira description from terminating the block it lives inside."""
    for marker in JIRA_MARKERS + TASK_MARKERS:
        text = text.replace(marker, marker.replace("<!--", "<!—"))
    return text


def jira_block(issue, base_url, closes):
    key = issue["key"]
    fields = issue["fields"]

    summary = fields.get("summary") or ""
    issue_type = (fields.get("issuetype") or {}).get("name") or "-"
    status = (fields.get("status") or {}).get("name") or "-"
    assignee = (fields.get("assignee") or {}).get("displayName") or "미지정"

    parent = fields.get("parent")
    epic = "-"
    if parent:
        epic = "[{}] {}".format(parent["key"], parent.get("fields", {}).get("summary", ""))

    lines = [
        "### 🎫 [{}] {}".format(key, summary),
        "",
        "| 타입 | 담당자 | Jira 상태 | 상위 |",
        "|---|---|---|---|",
        "| {} | {} | {} | {} |".format(issue_type, assignee, status, epic),
        "",
    ]

    description = strip_markers((fields.get("description") or "").strip())
    if description:
        if len(description) > COLLAPSE_OVER:
            lines += [
                "<details><summary>📄 Jira 설명 (길어서 접었습니다)</summary>",
                "",
                description,
                "",
                "</details>",
                "",
            ]
        else:
            lines += ["#### 📄 Jira 설명", "", description, ""]

    lines.append("🔗 {}/browse/{}".format(base_url.rstrip("/"), key))

    # This line is what closes the mirrored GitHub issue when the PR merges.
    if closes:
        lines += ["", "Closes #{}".format(closes)]

    return "\n".join(lines)


def task_block(commits, key):
    # Tolerate the key with or without brackets — hand-typed prefixes vary.
    prefix = re.compile(r"^\s*\[?" + re.escape(key) + r"\]?\s*", re.IGNORECASE)

    items = []
    for commit in commits:
        if len(commit.get("parents") or []) > 1:
            continue  # merge commit, not a unit of work
        subject = (commit["commit"]["message"] or "").split("\n")[0].strip()
        subject = prefix.sub("", subject).strip()
        if subject:
            items.append("- [x] {} (`{}`)".format(subject, commit["sha"][:7]))

    if not items:
        return "### ✅ 작업 내역\n\n_아직 커밋이 없습니다._"
    return "### ✅ 작업 내역\n\n" + "\n".join(items)


def main():
    data = json.load(sys.stdin)
    issue = data["issue"]
    body = data.get("body") or ""
    base_url = os.environ["JIRA_BASE_URL"]

    # Tasks first, then Jira: both get prepended, so this leaves the Jira
    # context on top where a reviewer reads it first.
    body = splice(body, TASK_MARKERS, task_block(data.get("commits") or [], issue["key"]))
    body = splice(body, JIRA_MARKERS, jira_block(issue, base_url, data.get("issue_number")))

    sys.stdout.write(body)


if __name__ == "__main__":
    main()
