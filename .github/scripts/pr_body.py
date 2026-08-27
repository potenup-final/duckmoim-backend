#!/usr/bin/env python3
"""Put the Jira ticket's context at the top of a pull request body.

The block sits between markers, so everything a person wrote around it is left
alone. The point is that a reviewer reads the requirement without leaving GitHub.

Reads one JSON object on stdin:
    {"issue": <Jira issue>, "body": "...", "issue_number": 12 | null}
Writes the new body on stdout.
"""

import json
import os
import re
import sys

MARKERS = ("<!-- jira:start -->", "<!-- jira:end -->")

# Jira descriptions can run long. Past this, the block is collapsed so the
# human-written part of the PR stays visible without scrolling past a spec.
COLLAPSE_OVER = 1200


def splice(body, block):
    """Replace the marker region, or prepend the block when the markers aren't there yet."""
    start, end = MARKERS
    region = re.compile(re.escape(start) + ".*?" + re.escape(end), re.DOTALL)
    new = "{}\n{}\n{}".format(start, block, end)
    if region.search(body):
        # A lambda, not a plain string: backslashes in Jira text would otherwise
        # be read as group references and blow up the substitution.
        return region.sub(lambda _: new, body, count=1)
    return new + "\n\n" + body


def strip_markers(text):
    """Keep a Jira description from terminating the block it lives inside."""
    for marker in MARKERS:
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


def main():
    data = json.load(sys.stdin)
    issue = data["issue"]
    body = data.get("body") or ""
    base_url = os.environ["JIRA_BASE_URL"]

    sys.stdout.write(splice(body, jira_block(issue, base_url, data.get("issue_number"))))


if __name__ == "__main__":
    main()
