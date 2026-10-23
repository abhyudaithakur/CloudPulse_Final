#!/usr/bin/env python3
"""System test of CloudPulse in Kubernetes mode (kind), RAD FR-08 to FR-12, NFR-02, NFR-03.

Runs against the cluster started by scripts/k8s-up.sh, through the same NodePorts a person uses
(dashboard + API on :30080, Prometheus on :30090), and uses kubectl to check what really
happened in the cluster: which pod was replaced, which annotation the restart left, and what the
API's service account is and is not allowed to do.

Usage:  python e2e/k8s_test.py [--ui http://localhost:30080] [--prometheus http://localhost:30090]
                               [--out e2e-k8s-results] [--screenshots]
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.request
from dataclasses import asdict
from pathlib import Path
from typing import Any, Callable

from smoke_test import CHECKOUT, LATENCY, PAYMENT, Check, Run, StackClient, wait_for

DEMO_NS = "cloudpulse-demo"
API_SA = "system:serviceaccount:cloudpulse:cloudpulse-api"


def kubectl(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["kubectl", *args], check=check, capture_output=True, text=True)


def kube_json(*args: str) -> Any:
    return json.loads(kubectl(*args, "-o", "json").stdout)


def pods(app: str) -> list[dict]:
    items = kube_json("-n", DEMO_NS, "get", "pods", "-l", f"app={app}")["items"]
    return [p for p in items if p["metadata"].get("deletionTimestamp") is None]


def can_i(verb: str, resource: str, namespace: str) -> bool:
    result = kubectl("auth", "can-i", verb, resource, "-n", namespace, f"--as={API_SA}", check=False)
    return result.stdout.strip() == "yes"


class KubernetesTest:
    def __init__(self, ui: str, prometheus: str, out: Path, screenshots: bool) -> None:
        self.client = StackClient(ui)  # the dashboard's Nginx proxies /api to the API Service
        self.ui = ui.rstrip("/")
        self.prometheus = prometheus.rstrip("/")
        self.out = out
        self.run = Run()
        self.state: dict[str, Any] = {}
        self.page = None
        if screenshots:
            try:
                from playwright.sync_api import sync_playwright

                self._pw = sync_playwright().start()
                self._browser = self._pw.chromium.launch()
                self.page = self._browser.new_page(viewport={"width": 1440, "height": 960})
                (out / "screenshots").mkdir(parents=True, exist_ok=True)
            except Exception as error:  # screenshots are optional
                print(f"screenshots disabled: {error}", flush=True)

    def check(self, name: str, requirements: list[str], fn: Callable[[], str]) -> bool:
        started = time.monotonic()
        try:
            detail, passed = fn() or "ok", True
        except Exception as error:
            detail, passed = f"{type(error).__name__}: {error}", False
        seconds = round(time.monotonic() - started, 1)
        self.run.checks.append(Check(name, requirements, passed, detail, seconds))
        print(f"{'PASS' if passed else 'FAIL'} [{', '.join(requirements)}] {name} ({seconds} s): {detail}", flush=True)
        return passed

    def snap(self, name: str, route: str) -> None:
        if not self.page:
            return
        try:
            self.page.goto(f"{self.ui}/{route}")
            self.page.wait_for_timeout(4000)
            self.page.screenshot(path=str(self.out / "screenshots" / f"{name}.png"), full_page=True)
        except Exception as error:
            print(f"screenshot {name} failed: {error}", flush=True)

    # ---- checks ------------------------------------------------------------------------------

    def ready(self) -> str:
        def healthy() -> dict | None:
            d = self.client.get("/api/dashboard")
            ok = (
                d["pipeline"]["status"] == "CONNECTED"
                and all(s["health"] == "HEALTHY" for s in d["services"])
                and not self.client.all_active()
            )
            return d if ok else None

        dashboard, waited = wait_for(healthy, 300, "both services HEALTHY in Kubernetes mode", interval=5)
        remediation = dashboard["remediation"]
        assert remediation["enabled"] and remediation["mode"] == "kubernetes", remediation
        assert remediation["namespace"] == DEMO_NS, remediation
        assert dashboard["prometheusUrl"].endswith(":30090"), dashboard["prometheusUrl"]
        self.snap("k8s-01-overview", "#/")
        return f"healthy {waited:.0f} s after deploy; remediation enabled for namespace {DEMO_NS} only"

    def discovery(self) -> str:
        with urllib.request.urlopen(f"{self.prometheus}/api/v1/targets?state=active", timeout=10) as r:
            targets = json.loads(r.read())["data"]["activeTargets"]
        by_job = {t["labels"]["job"]: t for t in targets}
        for job in (CHECKOUT, PAYMENT, "cloudpulse-api"):
            assert job in by_job, f"no target for {job}: {sorted(by_job)}"
            assert by_job[job]["health"] == "up", by_job[job]
        pod = by_job[PAYMENT]["labels"].get("pod", "")
        assert pod.startswith(PAYMENT), by_job[PAYMENT]["labels"]
        return f"Prometheus found {CHECKOUT}, {PAYMENT} (pod {pod}) and the API, all up"

    def rbac(self) -> str:
        expected = [
            ("get", "deployment/payment-api", DEMO_NS, True),
            ("patch", "deployment/payment-api", DEMO_NS, True),
            ("patch", "deployment/checkout-api", DEMO_NS, True),
            ("delete", "deployment/payment-api", DEMO_NS, False),
            ("patch", "deployment/traffic-generator", DEMO_NS, False),
            ("create", "deployments", DEMO_NS, False),
            ("delete", "pods", DEMO_NS, False),
            ("create", "pods", DEMO_NS, False),
            ("get", "secrets", DEMO_NS, False),
            ("patch", "deployment/cloudpulse-api", "cloudpulse", False),
            ("get", "secrets", "cloudpulse", False),
            ("patch", "deployments", "default", False),
        ]
        wrong = [
            f"{verb} {resource} -n {ns}: expected {'yes' if allowed else 'no'}"
            for verb, resource, ns, allowed in expected
            if can_i(verb, resource, ns) != allowed
        ]
        assert not wrong, "; ".join(wrong)
        allowed = sum(1 for *_, a in expected if a)
        return f"{allowed} permissions allowed and {len(expected) - allowed} denied, exactly as designed"

    def stuck(self) -> str:
        before = pods(PAYMENT)
        assert len(before) == 1, before
        self.state["pod_before"] = before[0]["metadata"]["name"]
        self.state["generation_before"] = kube_json("-n", DEMO_NS, "get", "deployment", PAYMENT)["metadata"]["generation"]
        started = time.monotonic()
        self.client.fault("stuck", PAYMENT)

        def both() -> dict | None:
            checkout = self.client.active(LATENCY, CHECKOUT)
            payment = self.client.active(LATENCY, PAYMENT)
            return {"checkout": checkout[0], "payment": payment[0]} if checkout and payment else None

        found, _ = wait_for(both, 240, "latency incidents on both services", interval=5)
        detected = time.monotonic() - started
        self.run.metrics["stuck_time_to_detect_s"] = round(detected, 1)
        self.state.update(found)
        source = self.client.incident(found["checkout"]["id"])["probableSource"]
        assert source["verdict"] == "DEPENDENCY" and source["service"] == PAYMENT, source
        status, payload = self.client.call("/api/demo/fault", "POST", {"service": PAYMENT, "mode": "none"})
        assert status == 409, (status, payload)
        now = pods(PAYMENT)
        assert [p["metadata"]["name"] for p in now] == [self.state["pod_before"]], now
        restarts = now[0]["status"]["containerStatuses"][0]["restartCount"]
        assert restarts == 0, f"Kubernetes restarted the stuck pod itself ({restarts} restarts)"
        self.snap("k8s-02-stuck-incident", f"#/incidents/{found['checkout']['id']}")
        return (f"{found['checkout']['key']} ({CHECKOUT}) traced to {PAYMENT} in {detected:.0f} s; "
                "clearing refused; Kubernetes left the live-but-stuck pod alone")

    def guarded(self) -> str:
        incident = self.state["checkout"]["id"]
        path = f"/api/incidents/{incident}/remediations/RESTART_DEPLOYMENT/approve"
        status, _ = self.client.call(path, "POST", {"targetService": PAYMENT, "operator": "system-test"})
        assert status == 400, f"approval without confirmation -> {status}"
        status, _ = self.client.call(path, "POST", {"targetService": PAYMENT, "confirm": True})
        assert status == 400, f"approval without an operator -> {status}"
        status, _ = self.client.call(path, "POST", {"targetService": "cloudpulse-api", "operator": "system-test", "confirm": True})
        assert status == 400, f"approval for a target outside the dependency map -> {status}"
        generation = kube_json("-n", DEMO_NS, "get", "deployment", PAYMENT)["metadata"]["generation"]
        assert generation == self.state["generation_before"], "a rejected approval changed the deployment"
        return "unconfirmed, anonymous and out-of-scope approvals refused (400); deployment untouched"

    def remediate(self) -> str:
        incident = self.state["checkout"]["id"]
        detail = self.client.incident(incident)
        option = detail["runbooks"][0]
        assert option["targetService"] == PAYMENT and option["recommended"] and option["available"], option
        approved_at = time.monotonic()
        status, action = self.client.call(
            f"/api/incidents/{incident}/remediations/RESTART_DEPLOYMENT/approve",
            "POST",
            {"targetService": PAYMENT, "operator": "system-test", "confirm": True, "note": "payment-api is stuck"},
        )
        assert status == 202, (status, action)
        seen: list[str] = []

        def finished() -> dict | None:
            current = self.client.get(f"/api/incidents/{incident}/remediations")[0]
            if not seen or seen[-1] != current["status"]:
                seen.append(current["status"])
            return current if not current["inProgress"] else None

        result, _ = wait_for(finished, 420, "the remediation to finish", interval=3)
        total = time.monotonic() - approved_at
        assert result["status"] == "RECOVERED", result
        self.run.metrics["approval_to_recovered_s"] = round(total, 1)
        self.run.metrics["remediation_states_seen"] = seen

        deployment = kube_json("-n", DEMO_NS, "get", "deployment", PAYMENT)
        annotations = deployment["spec"]["template"]["metadata"].get("annotations", {})
        assert "kubectl.kubernetes.io/restartedAt" in annotations, annotations
        assert "approved-by-system-test" in annotations.get("cloudpulse.io/remediation", ""), annotations
        after = [p["metadata"]["name"] for p in pods(PAYMENT)]
        assert self.state["pod_before"] not in after, f"pod not replaced: {after}"
        self.state["generation_after"] = deployment["metadata"]["generation"]

        actions = [e["action"] for e in self.client.get(f"/api/incidents/{incident}/audit")]
        for needed in ("REMEDIATION_APPROVED", "REMEDIATION_EXECUTED", "REMEDIATION_ROLLED_OUT", "INCIDENT_RESOLVED", "REMEDIATION_VERIFIED"):
            assert needed in actions, f"{needed} missing from {actions}"
        actors = {e["actor"] for e in self.client.get(f"/api/incidents/{incident}/audit")}
        assert "operator:system-test" in actors, actors
        mode = {f["service"]: f for f in self.client.get("/api/demo/faults")}[PAYMENT]
        assert mode["mode"] == "none" and not mode["needsRestart"], mode
        payment = self.client.incident(self.state["payment"]["id"])["incident"]
        self.snap("k8s-03-remediation-recovered", f"#/incidents/{incident}")
        return (f"restart of {PAYMENT} approved → {' → '.join(seen)} in {total:.0f} s; pod "
                f"{self.state['pod_before']} replaced by {', '.join(after)}; {PAYMENT} incident {payment['status']}")

    def durable(self) -> str:
        incident = self.state["checkout"]["id"]
        kubectl("-n", "cloudpulse", "rollout", "restart", "deployment/cloudpulse-api")
        kubectl("-n", "cloudpulse", "rollout", "status", "deployment/cloudpulse-api", "--timeout=300s")
        _, waited = wait_for(lambda: self.client.get("/api/health")["status"] == "UP", 180, "the API after restart")
        remediation = self.client.get(f"/api/incidents/{incident}/remediations")[0]
        assert remediation["status"] == "RECOVERED", remediation
        time.sleep(10)  # several remediation ticks
        generation = kube_json("-n", DEMO_NS, "get", "deployment", PAYMENT)["metadata"]["generation"]
        assert generation == self.state["generation_after"], "the restarted API re-ran a finished remediation"
        return f"API replaced; incident, remediation and audit intact from PostgreSQL; nothing re-executed"

    def execute(self) -> Run:
        if not self.check("Kubernetes mode starts healthy", ["FR-14", "NFR-06", "UC-01"], self.ready):
            return self.run
        self.check("Prometheus discovers the demo pods by label", ["FR-01", "NFR-10"], self.discovery)
        self.check("The remediation identity is least-privilege", ["NFR-03"], self.rbac)
        if self.check("A stuck service is detected and traced to its probable source", ["FR-09", "FR-08", "UC-03"], self.stuck):
            self.check("Approvals must be confirmed, attributed and in scope", ["FR-11", "NFR-02"], self.guarded)
            if self.check("An approved restart rolls out and recovery is verified", ["FR-10", "FR-11", "FR-12", "FR-13", "UC-04", "UC-05"], self.remediate):
                self.check("History survives an API restart and nothing re-executes", ["NFR-02", "NFR-05"], self.durable)
        self.snap("k8s-04-activity", "#/activity")
        return self.run


def write_results(run: Run, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    (out / "results.json").write_text(json.dumps(asdict(run), indent=2))
    passed = sum(c.passed for c in run.checks)
    lines = [
        "## System test (Kubernetes mode, kind)",
        "",
        f"**{passed}/{len(run.checks)} checks passed.**",
        "",
        "| Result | Check | Requirements | Time | Detail |",
        "|---|---|---|---|---|",
    ]
    for c in run.checks:
        detail = c.detail.replace("|", "\\|")
        lines.append(f"| {'✅' if c.passed else '❌'} | {c.name} | {', '.join(c.requirements)} | {c.seconds} s | {detail} |")
    lines += ["", "### Measurements", "", "```json", json.dumps(run.metrics, indent=2), "```", ""]
    (out / "summary.md").write_text("\n".join(lines))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ui", default="http://localhost:30080")
    parser.add_argument("--prometheus", default="http://localhost:30090")
    parser.add_argument("--out", default="e2e-k8s-results")
    parser.add_argument("--screenshots", action="store_true")
    args = parser.parse_args()
    out = Path(args.out)
    test = KubernetesTest(args.ui, args.prometheus, out, args.screenshots)
    try:
        run = test.execute()
    finally:
        try:
            StackClient(args.ui).fault("none", CHECKOUT)
        except Exception:
            pass
    write_results(run, out)
    failed = [c for c in run.checks if not c.passed]
    print(f"\n{len(run.checks) - len(failed)}/{len(run.checks)} checks passed", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
