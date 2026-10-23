#!/usr/bin/env python3
"""Builds the requirement traceability matrix (RAD NFR-09) from real test results.

Reads JUnit XML (backend and demo-service Surefire/Failsafe, frontend Vitest) and the system
test's results.json, finds the RAD ids each test names (fr03_..., "NFR-04: ...", checks tagged
with requirements), and writes a Markdown matrix: every requirement, its status, and the tests
that verify it at unit, integration and system level.

Usage: python scripts/traceability_report.py --reports <dir> [--catalog docs/traceability/requirements.json]
                                             [--out traceability.md]
"""

from __future__ import annotations

import argparse
import json
import re
import xml.etree.ElementTree as ET
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

REQ = re.compile(r"(?<![A-Za-z])(NFR|FR|UC)[-_ ]?(\d{2})", re.IGNORECASE)


@dataclass
class Result:
    name: str
    level: str  # unit | integration | system
    passed: bool


def requirement_ids(text: str) -> set[str]:
    return {f"{prefix.upper()}-{number}" for prefix, number in REQ.findall(text)}


def junit_results(path: Path) -> list[tuple[set[str], Result]]:
    """Every executed test case with the requirement ids its name carries (possibly none)."""
    found: list[tuple[set[str], Result]] = []
    root = ET.parse(path).getroot()
    for case in root.iter("testcase"):
        name = case.get("name", "")
        classname = case.get("classname", "")
        if case.find("skipped") is not None:
            continue
        passed = case.find("failure") is None and case.find("error") is None
        if "/" in classname:  # Vitest reports the test file path as the class name
            label = f"{Path(classname).name}: {name}"
        elif classname:
            label = f"{classname.rsplit('.', 1)[-1]}.{name}"
        else:
            label = name
        integration = "failsafe" in str(path) or classname.endswith("IT")
        found.append((requirement_ids(name), Result(label, "integration" if integration else "unit", passed)))
    return found


def system_results(path: Path) -> list[tuple[str, Result]]:
    data = json.loads(path.read_text())
    found = []
    for check in data.get("checks", []):
        for req in check.get("requirements", []):
            found.append((req, Result(check["name"], "system", bool(check["passed"]))))
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--reports", required=True, help="directory searched recursively for *.xml and results.json")
    parser.add_argument("--catalog", default="docs/traceability/requirements.json")
    parser.add_argument("--out", default="traceability.md")
    args = parser.parse_args()

    catalog = json.loads(Path(args.catalog).read_text())
    by_req: dict[str, list[Result]] = defaultdict(list)
    all_results: list[Result] = []
    sources = 0
    for xml in sorted(Path(args.reports).rglob("*.xml")):
        try:
            cases = junit_results(xml)
        except ET.ParseError:
            continue
        sources += 1
        for reqs, result in cases:
            all_results.append(result)
            for req in reqs:
                by_req[req].append(result)
    # Requirements verified by whole test classes (e.g. NFR-08: modules tested in isolation).
    for item in catalog:
        for test_class in item.get("testClasses", []):
            members = [r for r in all_results if r.name.startswith(test_class + ".")]
            if members:
                summary = f"{test_class} ({len(members)} tests)"
                by_req[item["id"]].append(Result(summary, "unit", all(r.passed for r in members)))
    for results_json in sorted(Path(args.reports).rglob("results.json")):
        sources += 1
        for req, result in system_results(results_json):
            by_req[req].append(result)

    counts = {"verified": 0, "failing": 0, "partial": 0, "planned": 0, "untested": 0}
    rows = []
    for item in catalog:
        results = by_req.get(item["id"], [])
        failing = [r for r in results if not r.passed]
        if failing:
            status, key = "❌ failing", "failing"
        elif item["status"] == "planned":
            status, key = f"○ planned ({item.get('phase', 'later phase')})", "planned"
        elif item["status"] == "partial":
            status, key = "◐ partial", "partial"
        elif results:
            status, key = "✅ verified", "verified"
        elif item.get("verifiedBy") == "pipeline" and sources:
            status, key = "✅ verified (this pipeline)", "verified"
        else:
            status, key = "⚠ no automated test", "untested"
        counts[key] += 1

        def names(level: str) -> str:
            matched = sorted({r.name for r in results if r.level == level})
            return "<br>".join(matched) if matched else "—"

        note = f" _{item['note']}_" if item.get("note") else ""
        rows.append(
            f"| **{item['id']}** | {item['text']}{note} | {status} | {names('unit')} | "
            f"{names('integration')} | {names('system')} |"
        )

    total_tests = sum(len(v) for v in by_req.values())
    executed = {
        level: sum(1 for r in all_results if r.level == level) for level in ("unit", "integration")
    }
    system_checks = 0
    for results_json in sorted(Path(args.reports).rglob("results.json")):
        system_checks += len(json.loads(results_json.read_text()).get("checks", []))
    passed = sum(1 for r in all_results if r.passed)
    lines = [
        "## Requirement traceability (RAD v1.0)",
        "",
        f"Executed: {executed['unit']} unit tests, {executed['integration']} integration tests and "
        f"{system_checks} system-test checks ({passed}/{len(all_results)} automated tests passed).",
        "",
        f"Generated from {sources} result files and {total_tests} requirement-tagged test results. "
        f"✅ {counts['verified']} verified · ◐ {counts['partial']} partial · ○ {counts['planned']} planned · "
        f"⚠ {counts['untested']} without automated test · ❌ {counts['failing']} failing",
        "",
        "| ID | Requirement | Status | Unit tests | Integration tests | System test |",
        "|---|---|---|---|---|---|",
        *rows,
        "",
    ]
    Path(args.out).write_text("\n".join(lines))
    print("\n".join(lines[:6]))
    return 1 if counts["failing"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
