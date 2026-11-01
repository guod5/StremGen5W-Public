#!/usr/bin/env python3
"""Prevent hardcoded personal information from being included in public release."""
from pathlib import Path
import re
import subprocess
import sys

tracked = subprocess.check_output(["git", "ls-files"], text=True).splitlines()
extensions = (".java", ".kt", ".cc", ".cpp", ".gradle", ".py", ".sh",
              ".md", ".yml", ".yaml", ".xml", ".properties", ".json")
email_pattern = re.compile(r"(?i)\b[A-Z0-9._%+\-]+@[A-Z0-9.\-]+\.[A-Z]{2,}\b")
dangerous_pattern = re.compile(
    r"(?:sk-[A-Za-z0-9_-]{15,}|gh[opusr]_[A-Za-z0-9_]{20,}|"
    r"AIza[A-Za-z0-9_-]{25,}|(?:Bearer\s+)[A-Za-z0-9._-]{20,})"
)
# Third-party license notices can contain their licensors' contact addresses.
exceptions = {"app/src/main/assets/FFMPEG_LICENSE_LGPLv2.1.txt",
              "app/src/main/assets/FFMPEG_NOTICE.txt",
              "app/src/main/assets/cacert.pem"}
issues = []
for name in tracked:
    p = Path(name)
    if name in exceptions or p.suffix.lower() not in extensions:
        continue
    try:
        content = p.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        issues.append((name, "unreadable source"))
        continue
    for label, pattern in (("literal email", email_pattern),
                           ("possible credential", dangerous_pattern)):
        for match in pattern.finditer(content):
            issues.append((name, f"{label} at line {content.count(chr(10), 0, match.start()) + 1}"))
if issues:
    print("PUBLIC PRIVACY AUDIT FAILED")
    for name, detail in issues:
        print(f"{name}: {detail}")
    sys.exit(1)
print(f"PUBLIC PRIVACY AUDIT PASSED: checked {len(tracked)} tracked files")
