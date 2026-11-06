---
name: pr-triage
description: Triage open pull requests in a Bitbucket repository and say which need attention first. Use when asked what to review, which PRs are stale, or for a PR overview.
---

# Pull request triage

1. Call `list_pull_requests` for the repository (state OPEN).
2. For each pull request older than 3 days or with no reviewers, call `get_pull_request`.
3. Report a short table: id, title, author, age, why it needs attention.
4. Suggest an order to review them in. Don't post anything to Bitbucket.
