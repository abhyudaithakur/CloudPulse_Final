#!/usr/bin/env python3
"""Turns the interesting lines of a CI log into GitHub annotations.

Annotations show up on the run page and in the checks API, so a failed build explains itself
without anyone opening the raw logs. Used by .github/workflows/ci.yml on failure, and with
--notice to surface measurements from a summary file.

Usage: python scripts/ci/annotate.py <log> [--title T] [--notice] [--max-lines N]
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

INTERESTING = re.compile(
    r"(\[ERROR\]|error TS\d+|FAIL\b|AssertionError|Caused by:|Exception|Tests run:.*(Failures|Errors): [1-9]"
    r"|npm ERR!|Error:|failed|✗|×)",
    re.IGNORECASE,
)
NOISE = re.compile(r"(Download|Progress|\[INFO\]|^\s*at (java|org\.junit|org\.apache|jdk)\.)")


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("log")
    parser.add_argument("--title", default="CI failure")
    parser.add_argument("--notice", action="store_true", help="emit the whole file as one notice")
    parser.add_argument("--max-lines", type=int, default=120)
    args = parser.parse_args()

    path = Path(args.log)
    if not path.exists():
        print(f"::warning title={args.title}::{path} not found")
        return 0
    lines = path.read_text(errors="replace").splitlines()

    if args.notice:
        print(f"::notice title={args.title}::{escape(chr(10).join(lines[: args.max_lines]))}")
        return 0

    picked = [line for line in lines if INTERESTING.search(line) and not NOISE.search(line)]
    picked = picked[: args.max_lines] or lines[-40:]
    # GitHub keeps at most 10 error annotations per step, so batch lines into chunks.
    chunk = max(1, -(-len(picked) // 9))
    for start in range(0, len(picked), chunk):
        body = "\n".join(picked[start : start + chunk])
        print(f"::error title={args.title} ({start // chunk + 1})::{escape(body[:60000])}")
    tail = "\n".join(lines[-25:])
    print(f"::error title={args.title} (log tail)::{escape(tail[:60000])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
