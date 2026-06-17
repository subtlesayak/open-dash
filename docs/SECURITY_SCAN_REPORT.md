# Security Scan Report

Date: 2026-06-17

Scope:

- Current tracked repository files
- First-parent Git history for the current branch
- Text files only; binary media/build artifacts are skipped by the scanner
- Checks for email addresses, phone numbers, government-ID-shaped values, API keys, tokens, private keys, OAuth client IDs/secrets, password literals, public IPs, local absolute paths, and environment file references

Values below are intentionally masked.

| File path | Commit | Type | Still present in latest code | Masked value | Recommended fix |
| --- | --- | --- | --- | --- | --- |
| `app/src/main/java/com/example/northstar/ui/screens/LoginScreen.kt` | `fcbce4b0608b` | Google OAuth client ID | No | `246319****.com` | Current code has been moved to build-time config. Rotate/replace the OAuth credential in Google Cloud if it belongs to a private project. |

## Current Code Status

- Tracked sign-in logs containing personal/debug data were removed from the latest tree.
- The dash default password is no longer stored as a Kotlin literal. It is read from `OPENDASH_DASH_DEFAULT_PASSWORD`.
- The Google web client ID is no longer stored as a Kotlin literal. It is read from `OPENDASH_GOOGLE_WEB_CLIENT_ID`.
- `.env`, local config, logs, APKs, AABs, keystores, Firebase config, and build outputs are ignored.
- A tracked pre-commit hook and GitHub Actions workflow now run masked secret scans.

## Manual Action Needed

The historical OAuth client ID remains in Git history. History was not rewritten automatically.

If that credential is real/private, rotate it immediately in Google Cloud. If the repository history must be purged, coordinate with collaborators and use `git filter-repo` or BFG Repo-Cleaner as described in `docs/SECRET_HANDLING.md`.
