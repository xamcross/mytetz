# MINOR review findings

The owner decided this rule on 2026-09-22. The owner decided the file form on 2026-09-26.

- A MINOR finding is never fixed in the same pull request.
- A correction round fixes each BLOCKER and each MAJOR only.
- Each pull request has one file: `minor_issues/<pull request number>.md`.
- Each MINOR finding is one row in that file. The row holds the date, the issue, the reviewer role, the file and line, the finding, the suggested fix, and the status.
- The first status is `open`.
- Nobody removes a row. A later fix sets the status and the number of the pull request that fixes the finding.
- The rows stay until the final stage of the project, also after the production release.

The rows are in the folder [`minor_issues/`](minor_issues/).
