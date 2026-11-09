#!/usr/bin/env python3
"""Prints "<job> <change> <patchset> <ref> <commit>" for every patch set of an open Gerrit change not seen
before. GERRIT_POLL_PROJECTS maps projects to jobs: "demo-service=demo-service-verify,other=other-verify"."""

import json
import os
import urllib.parse
import urllib.request

GERRIT = os.environ.get("GERRIT_URL", "http://gerrit:8080").rstrip("/")
PROJECTS = os.environ.get("GERRIT_POLL_PROJECTS", "demo-service=demo-service-verify")
SEEN = os.path.join(os.environ.get("JENKINS_HOME", "/var/jenkins_home"), "gerrit-poll.seen")


def open_changes(project):
    query = urllib.parse.quote(f"status:open project:{project}")
    with urllib.request.urlopen(f"{GERRIT}/changes/?q={query}&o=CURRENT_REVISION", timeout=30) as response:
        return json.loads(response.read().decode().removeprefix(")]}'"))


def main():
    seen = set(open(SEEN).read().split()) if os.path.exists(SEEN) else set()
    found = []
    for mapping in filter(None, PROJECTS.split(",")):
        project, job = mapping.split("=", 1)
        for change in open_changes(project):
            commit = change["current_revision"]
            if commit in seen:
                continue
            revision = change["revisions"][commit]
            found.append(f"{job} {change['_number']} {revision['_number']} {revision['ref']} {commit}")
            seen.add(commit)
    with open(SEEN, "w") as file:
        file.write("\n".join(sorted(seen)))
    print("\n".join(found))


if __name__ == "__main__":
    main()
