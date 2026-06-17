# Secret Handling

OpenDash must not commit personal data, credentials, generated apps, logs, or local config.

## Store Secrets Safely

- Use Gradle properties, CI secrets, or local environment variables.
- Keep `.env`, `local.properties`, keystores, Firebase config, logs, APKs, and AABs out of git.
- Keep only placeholder values in `.env.example`.
- For Google sign-in, provide `OPENDASH_GOOGLE_WEB_CLIENT_ID` locally or in CI.
- For dash WiFi defaults, provide `OPENDASH_DASH_DEFAULT_PASSWORD` locally or in CI.
- For release signing, provide `OPENDASH_RELEASE_STORE_FILE`, `OPENDASH_RELEASE_STORE_PASSWORD`, `OPENDASH_RELEASE_KEY_ALIAS`, and `OPENDASH_RELEASE_KEY_PASSWORD` through Gradle properties or CI secrets.

## Local Hook

Install the tracked pre-commit hook once per clone:

```powershell
git config core.hooksPath .githooks
```

The hook scans staged files with `scripts/secret_scan.py` and writes a masked report to `build/security/pre-commit-secret-scan.md`.

## CI Protection

`.github/workflows/secret-scan.yml` scans pushes and pull requests on `main`, `beta`, and `experimental`. The workflow uploads only masked reports.

## If a Secret Was Committed

Do not rewrite public history casually. First:

1. Rotate or revoke any exposed key, token, password, certificate, or OAuth credential.
2. Tell collaborators to stop using the leaked value.
3. Remove the value from current code.
4. If history must be cleaned, coordinate a force-push window and use one of:

```powershell
git filter-repo --path path/to/file --invert-paths
```

or BFG Repo-Cleaner:

```powershell
bfg --delete-files path-or-pattern.git
```

After rewriting history, force-push all affected branches/tags and ask every clone owner to re-clone or hard-reset. GitHub release assets, Actions artifacts, forks, and local clones may still retain the original data, so key rotation is mandatory.
