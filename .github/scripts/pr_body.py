#!/usr/bin/env python3
"""Put the Jira ticket's identity at the top of a pull request body.

The block sits between markers, so everything a person wrote around it is left
alone. It carries who and which ticket, not the spec and not `Closes #N` — the
requirement is read in Jira, and the issue link is the template's own first
line for a person to fill in.

Reads one JSON object on stdin:
    {"issue": <Jira issue>, "body": "..."}
Writes the new body on stdout.
"""

import json
import os
import re
import sys

MARKERS = ("<!-- jira:start -->", "<!-- jira:end -->")


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


def jira_block(issue, base_url):
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

    lines.append("🔗 {}/browse/{}".format(base_url.rstrip("/"), key))

    return "\n".join(lines)


def main():
    data = json.load(sys.stdin)
    issue = data["issue"]
    body = data.get("body") or ""
    base_url = os.environ["JIRA_BASE_URL"]

    sys.stdout.write(splice(body, jira_block(issue, base_url)))


if __name__ == "__main__":
    main()
