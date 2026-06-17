#!/usr/bin/env python3
"""Masked secret and personal-data scanner for OpenDash.

The scanner intentionally reports only masked snippets. It scans both tracked
files and git history without printing raw secret values.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[1]

SKIP_DIRS = {
    ".git",
    ".gradle",
    ".idea",
    ".codex",
    "build",
    ".kotlin",
}

SKIP_EXTS = {
    ".apk",
    ".aab",
    ".jks",
    ".keystore",
    ".png",
    ".jpg",
    ".jpeg",
    ".gif",
    ".webp",
    ".mp4",
    ".mov",
    ".heic",
    ".ico",
    ".pdf",
}

PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("private key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("google api key", re.compile(r"\bAIza[0-9A-Za-z_\-]{20,}\b")),
    ("github token", re.compile(r"\bgh[pousr]_[0-9A-Za-z_]{20,}\b")),
    ("generic bearer token", re.compile(r"\bBearer\s+[A-Za-z0-9._\-]{20,}\b", re.I)),
    ("google oauth client id", re.compile(r"\b\d{8,}-[a-z0-9_\-]+\.apps\.googleusercontent\.com\b", re.I)),
    ("oauth/client secret", re.compile(r"(?i)\b(client_secret|oauth_secret|api_secret|secret_key)\b\s*[:=]\s*['\"]?([A-Za-z0-9_\-./+=]{8,})")),
    ("password assignment", re.compile(r"(?i)\b(password|passwd|pwd)\b\s*=\s*['\"]([^'\"]{6,})['\"]")),
    ("firebase/google config", re.compile(r"(?i)\b(firebase|google_maps|maps_api|api_key|application_id|project_id)\b\s*[:=]\s*['\"]?([^'\"\s,}]{6,})")),
    ("email address", re.compile(r"\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b")),
    ("phone number", re.compile(r"(?<!\d)(?:\+?91[\s\-]?)?[6-9]\d{9}(?!\d)")),
    ("aadhaar-like id", re.compile(r"(?<!\d)\d{4}[\s\-]?\d{4}[\s\-]?\d{4}(?!\d)")),
    ("pan-like id", re.compile(r"\b[A-Z]{5}[0-9]{4}[A-Z]\b")),
    ("passport-like id", re.compile(r"\b[A-Z][0-9]{7}\b")),
    ("public ip address", re.compile(r"\b(?!(?:10|127|169\.254|172\.(?:1[6-9]|2\d|3[0-1])|192\.168)\.)(?:[1-9]\d?|1\d\d|2[0-4]\d|25[0-5])(?:\.(?:\d{1,2}|1\d\d|2[0-4]\d|25[0-5])){3}\b")),
    ("environment file reference", re.compile(r"(^|/)\.env(?:\.[A-Za-z0-9_\-]+)?$", re.I)),
    ("local absolute path", re.compile(r"\b[A-Z]:\\Users\\[^\\\s]+", re.I)),
]

FALSE_POSITIVE_TERMS = (
    "example.com",
    "placeholder",
    "replace_with",
    "your_",
    "fake",
    "dummy",
    "localhost",
    "127.0.0.1",
)


@dataclass(frozen=True)
class Finding:
    path: str
    commit: str
    kind: str
    masked: str
    present_in_latest: bool
    recommendation: str


def run_git(args: list[str], check: bool = True) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        check=check,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return completed.stdout


def mask(value: str) -> str:
    value = value.strip()
    if not value:
        return ""
    if "@" in value and len(value) > 5:
        local, _, domain = value.partition("@")
        return f"{local[:3]}***@{domain[:1]}***"
    compact = value.replace("\n", "\\n")
    if len(compact) <= 8:
        return compact[:1] + "***"
    return f"{compact[:6]}****{compact[-4:]}"


def recommendation(kind: str) -> str:
    if "key" in kind or "token" in kind or "secret" in kind or "password" in kind:
        return "Remove from source, move to local Gradle/env secrets, and rotate immediately if real."
    if "email" in kind or "phone" in kind or "id" in kind or "path" in kind:
        return "Replace with neutral sample data and avoid committing personal details."
    if "ip address" in kind:
        return "Confirm this is not a private production endpoint; move environment-specific endpoints to config."
    return "Review and replace with a placeholder if this is not intentional public test data."


def should_skip_path(path: str) -> bool:
    p = Path(path)
    if any(part in SKIP_DIRS for part in p.parts):
        return True
    if p.suffix.lower() in SKIP_EXTS:
        return True
    return False


def is_probable_false_positive(kind: str, text: str) -> bool:
    lowered = text.lower()
    if lowered.startswith("this@"):
        return True
    if lowered in {"password", "passwd", "pwd"}:
        return True
    if lowered.startswith("buildconfig."):
        return True
    if kind != "google oauth client id" and "apps.googleusercontent.com" in lowered:
        return True
    return any(term in lowered for term in FALSE_POSITIVE_TERMS)


def scan_text(path: str, commit: str, text: str, latest_paths: set[str]) -> list[Finding]:
    findings: list[Finding] = []
    if should_skip_path(path):
        return findings
    for kind, pattern in PATTERNS:
        if kind == "environment file reference":
            if Path(path).name == ".env.example":
                continue
            if pattern.search(path):
                findings.append(
                    Finding(
                        path=path,
                        commit=commit,
                        kind=kind,
                        masked=mask(path),
                        present_in_latest=path in latest_paths,
                        recommendation=recommendation(kind),
                    )
                )
            continue
        for match in pattern.finditer(text):
            value = match.group(match.lastindex or 0)
            line_start = text.rfind("\n", 0, match.start()) + 1
            line_end = text.find("\n", match.end())
            line = text[line_start : line_end if line_end != -1 else len(text)]
            if kind == "aadhaar-like id" and "apps.googleusercontent.com" in line.lower():
                continue
            if is_probable_false_positive(kind, value):
                continue
            findings.append(
                Finding(
                    path=path,
                    commit=commit,
                    kind=kind,
                    masked=mask(value),
                    present_in_latest=path in latest_paths,
                    recommendation=recommendation(kind),
                )
            )
    return findings


def tracked_files() -> list[str]:
    return [line for line in run_git(["ls-files"]).splitlines() if line]


def latest_findings() -> list[Finding]:
    latest = set(tracked_files())
    findings: list[Finding] = []
    for path in sorted(latest):
        if should_skip_path(path):
            continue
        data = (ROOT / path).read_bytes()
        if b"\x00" in data:
            continue
        text = data.decode("utf-8", errors="replace")
        findings.extend(scan_text(path, "WORKTREE", text, latest))
    return findings


def history_findings() -> list[Finding]:
    latest = set(tracked_files())
    commits = [line for line in run_git(["rev-list", "--first-parent", "HEAD"]).splitlines() if line]
    findings: list[Finding] = []
    for commit in commits:
        files = run_git(["diff-tree", "--no-commit-id", "--name-only", "-r", commit]).splitlines()
        for path in files:
            if not path or should_skip_path(path):
                continue
            blob = subprocess.run(
                ["git", "show", f"{commit}:{path}"],
                cwd=ROOT,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
            )
            if blob.returncode != 0 or b"\x00" in blob.stdout:
                continue
            text = blob.stdout.decode("utf-8", errors="replace")
            findings.extend(scan_text(path, commit[:12], text, latest))
    return findings


def dedupe(findings: Iterable[Finding]) -> list[Finding]:
    seen: set[tuple[str, str, str, str]] = set()
    unique: list[Finding] = []
    for finding in findings:
        key = (finding.path, finding.commit, finding.kind, finding.masked)
        if key in seen:
            continue
        seen.add(key)
        unique.append(finding)
    return unique


def write_report(findings: list[Finding], report_path: Path, json_path: Path | None) -> None:
    report_path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Secret Scan Report",
        "",
        "Values are intentionally masked. Review the source locally if remediation is required.",
        "",
        f"Findings: {len(findings)}",
        "",
        "| File path | Commit | Type | Still present | Masked value | Recommended fix |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for finding in sorted(findings, key=lambda item: (item.path, item.commit, item.kind)):
        lines.append(
            "| {path} | {commit} | {kind} | {present} | `{masked}` | {fix} |".format(
                path=finding.path.replace("|", "\\|"),
                commit=finding.commit,
                kind=finding.kind,
                present="yes" if finding.present_in_latest else "no",
                masked=finding.masked.replace("|", "\\|"),
                fix=finding.recommendation.replace("|", "\\|"),
            )
        )
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    if json_path is not None:
        json_path.parent.mkdir(parents=True, exist_ok=True)
        json_path.write_text(json.dumps([asdict(f) for f in findings], indent=2), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--history", action="store_true", help="Scan first-parent commit history.")
    parser.add_argument("--staged", action="store_true", help="Scan staged files only.")
    parser.add_argument("--report", default="build/security/secret-scan.md")
    parser.add_argument("--json-out", default="")
    args = parser.parse_args()

    if args.staged:
        paths = [
            line
            for line in run_git(["diff", "--cached", "--name-only", "--diff-filter=ACMR"]).splitlines()
            if line
        ]
        latest = set(tracked_files())
        findings: list[Finding] = []
        for path in paths:
            if should_skip_path(path):
                continue
            blob = subprocess.run(
                ["git", "show", f":{path}"],
                cwd=ROOT,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
            )
            if blob.returncode == 0 and b"\x00" not in blob.stdout:
                findings.extend(scan_text(path, "STAGED", blob.stdout.decode("utf-8", errors="replace"), latest))
    else:
        findings = latest_findings()
        if args.history:
            findings.extend(history_findings())

    findings = dedupe(findings)
    write_report(findings, ROOT / args.report, ROOT / args.json_out if args.json_out else None)
    print(f"Secret scan completed. Masked findings: {len(findings)}. Report: {args.report}")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
