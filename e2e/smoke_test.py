#!/usr/bin/env python3
"""System test of the running Docker Compose stack (RAD NFR-09, system level).

Drives the real demo exactly as an operator would: inject each fault through the API, wait for
detection, check evidence, probable source and audit records, clear the fault, wait for recovery.
It also stops Prometheus and a demo service to prove outages are reported honestly, and puts the
API under concurrent load. Every check names the RAD requirements it verifies, and measured
timings are written out for the Test Plan.

Usage:  python e2e/smoke_test.py [--api http://localhost:8080] [--ui http://localhost:3000]
                                 [--out e2e-results] [--screenshots]
Needs:  the stack running (docker compose up --build -d) and the docker CLI for outage tests.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable

LATENCY = "LATENCY_P95_SUSTAINED"
ERRORS = "ERROR_RATE_SUSTAINED"
AVAILABILITY = "TARGET_AVAILABILITY"
CHECKOUT = "checkout-api"
PAYMENT = "payment-api"


@dataclass
class Check:
    name: str
    requirements: list[str]
    passed: bool
    detail: str
    seconds: float


@dataclass
class Run:
    checks: list[Check] = field(default_factory=list)
    metrics: dict[str, Any] = field(default_factory=dict)


class StackClient:
    def __init__(self, api: str) -> None:
        self.api = api.rstrip("/")

    def call(self, path: str, method: str = "GET", body: dict | None = None) -> tuple[int, Any]:
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(
            self.api + path,
            data=data,
            method=method,
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return response.status, json.loads(response.read() or b"null")
        except urllib.error.HTTPError as error:
            payload = error.read()
            try:
                return error.code, json.loads(payload or b"null")
            except json.JSONDecodeError:
                return error.code, payload.decode(errors="replace")

    def get(self, path: str) -> Any:
        status, payload = self.call(path)
        if status != 200:
            raise RuntimeError(f"GET {path} -> {status}: {payload}")
        return payload

    def fault(self, mode: str, service: str = CHECKOUT) -> None:
        status, payload = self.call("/api/demo/fault", "POST", {"service": service, "mode": mode})
        if status != 200:
            raise RuntimeError(f"setting fault {mode} on {service} -> {status}: {payload}")

    def active(self, rule: str, service: str = CHECKOUT) -> list[dict]:
        return self.get(f"/api/incidents?status=active&rule={rule}&service={service}")

    def all_active(self) -> list[dict]:
        return self.get("/api/incidents?status=active")

    def incident(self, incident_id: int) -> dict:
        return self.get(f"/api/incidents/{incident_id}")


def wait_for(probe: Callable[[], Any], timeout: float, what: str, interval: float = 2.5) -> tuple[Any, float]:
    """Polls until probe() returns something truthy; returns (value, seconds waited)."""
    started = time.monotonic()
    last_error: Exception | None = None
    while time.monotonic() - started < timeout:
        try:
            value = probe()
            if value:
                return value, time.monotonic() - started
        except Exception as error:  # the stack may be restarting; keep polling
            last_error = error
        time.sleep(interval)
    suffix = f" (last error: {last_error})" if last_error else ""
    raise TimeoutError(f"timed out after {timeout:.0f} s waiting for {what}{suffix}")


def compose(*args: str) -> None:
    subprocess.run(["docker", "compose", *args], check=True, capture_output=True, text=True)


class SmokeTest:
    def __init__(self, client: StackClient, ui: str, out: Path, screenshots: bool) -> None:
        self.client = client
        self.ui = ui.rstrip("/")
        self.out = out
        self.run = Run()
        self.browser = None
        self.page = None
        if screenshots:
            self._start_browser()

    # ---- reporting ---------------------------------------------------------------------------

    def check(self, name: str, requirements: list[str], fn: Callable[[], str]) -> None:
        started = time.monotonic()
        try:
            detail = fn() or "ok"
            passed = True
        except Exception as error:  # record and continue so one failure does not hide the rest
            detail = f"{type(error).__name__}: {error}"
            passed = False
        seconds = round(time.monotonic() - started, 1)
        self.run.checks.append(Check(name, requirements, passed, detail, seconds))
        marker = "PASS" if passed else "FAIL"
        print(f"{marker} [{', '.join(requirements)}] {name} ({seconds} s): {detail}", flush=True)

    def _start_browser(self) -> None:
        try:
            from playwright.sync_api import sync_playwright
        except ImportError:
            print("playwright not installed; skipping screenshots", flush=True)
            return
        try:
            self._playwright = sync_playwright().start()
            self.browser = self._playwright.chromium.launch()
            self.page = self.browser.new_page(viewport={"width": 1440, "height": 960})
            (self.out / "screenshots").mkdir(parents=True, exist_ok=True)
        except Exception as error:  # screenshots are optional; the checks still run
            print(f"browser unavailable, skipping screenshots: {error}", flush=True)
            self.browser = self.page = None

    def snap(self, name: str, hash_route: str = "#/") -> None:
        if not self.page:
            return
        try:
            self.page.goto(f"{self.ui}/{hash_route}")
            self.page.wait_for_timeout(4000)
            self.page.screenshot(path=str(self.out / "screenshots" / f"{name}.png"), full_page=True)
        except Exception as error:
            print(f"screenshot {name} failed: {error}", flush=True)

    def close(self) -> None:
        if self.browser:
            self.browser.close()
            self._playwright.stop()

    # ---- scenarios ---------------------------------------------------------------------------

    def stack_ready(self) -> str:
        wait_for(lambda: self.client.get("/api/health")["status"] == "UP", 240, "API health")

        _, waited = wait_for(self.all_healthy, 240, "both services HEALTHY with telemetry CONNECTED")
        self.snap("01-overview-healthy")
        services = self.client.get("/api/services")
        names = [s["name"] for s in services]
        assert names == [CHECKOUT, PAYMENT], names
        assert services[0]["dependsOn"] == [PAYMENT], services[0]
        return f"{' and '.join(names)} HEALTHY {waited:.0f} s after the API answered"

    def all_healthy(self) -> bool:
        d = self.client.get("/api/dashboard")
        return (
            d["pipeline"]["status"] == "CONNECTED"
            and d["health"] == "HEALTHY"
            and all(s["health"] == "HEALTHY" for s in d["services"])
            and not self.client.all_active()
        )

    def latency_incident(self) -> None:
        state: dict[str, Any] = {}

        def detect() -> str:
            self.client.fault("high-latency")
            incidents, waited = wait_for(lambda: self.client.active(LATENCY), 180, "a latency incident")
            state["id"] = incidents[0]["id"]
            state["detect"] = waited
            self.run.metrics["latency_time_to_detect_s"] = round(waited, 1)
            self.run.metrics["latency_detection_delay_s"] = incidents[0]["detectionDelaySeconds"]
            return (f"{incidents[0]['key']} opened {waited:.0f} s after injection, severity "
                    f"{incidents[0]['severity']}, p95 {incidents[0]['lastValue']:.3f} s")

        self.check("Latency fault opens an incident", ["FR-01", "FR-02", "FR-03", "FR-09", "UC-02", "UC-06"], detect)
        if "id" not in state:
            return

        def evidence() -> str:
            detail = self.client.incident(state["id"])
            first = detail["evidence"][0]
            assert first["kind"] == "BREACH_SUSTAINED", first
            assert first["metric"] == LATENCY and first["source"] == "prometheus", first
            assert first["observedValue"] >= first["threshold"] == 1.0, first
            assert first["observedAt"] and "histogram_quantile" in first["promql"], first
            assert detail["timeline"][0]["action"] == "INCIDENT_OPENED", detail["timeline"]
            assert detail["runbooks"], "latency incidents list the restart runbook"
            assert detail["probableSource"]["verdict"] == "SELF", detail["probableSource"]
            assert detail["probableSource"]["service"] == CHECKOUT, detail["probableSource"]
            return (f"evidence {first['observedValue']:.3f} s vs threshold {first['threshold']} s with PromQL; "
                    f"probable source {CHECKOUT} itself (payment-api healthy)")

        self.check("Incident carries evidence, timeline and runbook", ["FR-05", "FR-06", "FR-07", "FR-08", "FR-13", "UC-03"], evidence)

        def detection_delay() -> str:
            delay = self.client.incident(state["id"])["incident"]["detectionDelaySeconds"]
            assert delay <= 30, f"delay {delay} s exceeds 30 s"
            return f"opened {delay:.1f} s after the 30 s window was satisfied (limit 30 s)"

        self.check("Incident appears within 30 s of window satisfaction", ["NFR-01"], detection_delay)

        def dedup() -> str:
            time.sleep(12)
            active = self.client.active(LATENCY)
            assert len(active) == 1 and active[0]["id"] == state["id"], active
            return "still exactly one ACTIVE latency incident after 12 more seconds of breach"

        self.check("Sustained fault stays one incident", ["FR-04"], dedup)
        self.snap("02-overview-incident")
        self.snap("03-incident-detail", f"#/incidents/{state['id']}")

        def escalate() -> str:
            self.client.fault("extreme-latency")

            def critical() -> dict | None:
                incident = self.client.incident(state["id"])["incident"]
                return incident if incident["severity"] == "CRITICAL" else None

            incident, waited = wait_for(critical, 150, "escalation to CRITICAL")
            assert self.client.active(LATENCY)[0]["id"] == state["id"]
            return f"same incident escalated to CRITICAL in {waited:.0f} s (peak {incident['peakValue']:.2f} s)"

        self.check("Extreme latency escalates the same incident", ["FR-03", "FR-04", "FR-13"], escalate)

        def recover() -> str:
            self.client.fault("none")
            _, waited = wait_for(
                lambda: self.client.incident(state["id"])["incident"]["status"] == "RESOLVED",
                240,
                "resolution",
            )
            self.run.metrics["latency_time_to_resolve_s"] = round(waited, 1)
            detail = self.client.incident(state["id"])
            actions = [event["action"] for event in detail["timeline"]]
            assert actions[0] == "INCIDENT_OPENED" and "SEVERITY_ESCALATED" in actions, actions
            assert actions[-1] == "INCIDENT_RESOLVED", actions
            assert detail["evidence"][-1]["kind"] == "RECOVERY_CONFIRMED", detail["evidence"][-1]
            return f"resolved {waited:.0f} s after clearing the fault; audit {' → '.join(actions)}"

        self.check("Clearing the fault resolves it after the recovery window", ["UC-05", "FR-12", "FR-13"], recover)

    def error_incident(self) -> None:
        state: dict[str, Any] = {}

        def detect() -> str:
            self.client.fault("error-storm")
            incidents, waited = wait_for(lambda: self.client.active(ERRORS), 180, "an error-rate incident")
            state["id"] = incidents[0]["id"]
            self.run.metrics["errors_time_to_detect_s"] = round(waited, 1)
            assert incidents[0]["severity"] == "CRITICAL", incidents[0]
            return (f"{incidents[0]['key']} opened {waited:.0f} s after injection at "
                    f"{incidents[0]['lastValue'] * 100:.0f}% 5xx, CRITICAL")

        self.check("HTTP error fault opens an error-rate incident", ["FR-02", "FR-03", "FR-09"], detect)
        if "id" not in state:
            self.client.fault("none")
            return

        def recover() -> str:
            self.client.fault("none")
            _, waited = wait_for(
                lambda: self.client.incident(state["id"])["incident"]["status"] == "RESOLVED", 240, "resolution"
            )
            self.run.metrics["errors_time_to_resolve_s"] = round(waited, 1)
            return f"resolved {waited:.0f} s after clearing the fault"

        self.check("Error-rate incident resolves after recovery", ["UC-05", "UC-06"], recover)

    def probable_source(self) -> None:
        """FR-08 / UC-03: a payment failure also breaks checkout; CloudPulse names payment."""
        state: dict[str, Any] = {}

        def correlate() -> str:
            self.client.fault("error-storm", PAYMENT)

            def both() -> dict | None:
                checkout = self.client.active(ERRORS, CHECKOUT)
                payment = self.client.active(ERRORS, PAYMENT)
                return {"checkout": checkout[0], "payment": payment[0]} if checkout and payment else None

            found, waited = wait_for(both, 180, "error-rate incidents on both services")
            state.update(found)
            self.run.metrics["probable_source_time_to_detect_s"] = round(waited, 1)
            detail = self.client.incident(found["checkout"]["id"])
            source = detail["probableSource"]
            assert source["verdict"] == "DEPENDENCY" and source["service"] == PAYMENT, source
            assert found["checkout"]["probableSource"] == PAYMENT, found["checkout"]
            assert detail["runbooks"][0]["targetService"] == PAYMENT and detail["runbooks"][0]["recommended"], detail["runbooks"]
            own = self.client.incident(found["payment"]["id"])["probableSource"]
            assert own["verdict"] == "SELF" and any(r["relation"] == "dependent" for r in own["related"]), own
            return (f"{found['checkout']['key']} on {CHECKOUT} names {PAYMENT} as probable source "
                    f"({found['payment']['key']}); detected {waited:.0f} s after injection")

        self.check("A dependency failure is traced to its probable source", ["FR-08", "UC-03", "FR-02", "FR-07"], correlate)
        if "checkout" in state:
            self.snap("07-probable-source", f"#/incidents/{state['checkout']['id']}")

        def recover() -> str:
            self.client.fault("none", PAYMENT)
            if "checkout" not in state:
                return "fault cleared"
            ids = [state["checkout"]["id"], state["payment"]["id"]]
            _, waited = wait_for(
                lambda: all(self.client.incident(i)["incident"]["status"] == "RESOLVED" for i in ids),
                240,
                "both incidents resolved",
            )
            return f"both incidents resolved {waited:.0f} s after payment-api recovered"

        self.check("Fixing the dependency resolves both incidents", ["UC-05", "FR-08"], recover)

    def stuck_service(self) -> None:
        """FR-09: a stuck process ignores 'clear'; Compose mode explains but never restarts."""
        state: dict[str, Any] = {}

        def stuck() -> str:
            self.client.fault("stuck", PAYMENT)
            status, payload = self.client.call("/api/demo/fault", "POST", {"service": PAYMENT, "mode": "none"})
            assert status == 409, (status, payload)
            assert "restart" in payload["detail"], payload
            incidents, waited = wait_for(lambda: self.client.active(LATENCY, PAYMENT), 180, "a payment latency incident")
            state["id"] = incidents[0]["id"]
            detail = self.client.incident(state["id"])
            option = detail["runbooks"][0]
            assert option["targetService"] == PAYMENT and not option["available"], option
            status, payload = self.client.call(
                f"/api/incidents/{state['id']}/remediations/RESTART_DEPLOYMENT/approve",
                "POST",
                {"operator": "system-test", "confirm": True},
            )
            assert status == 409, (status, payload)
            faults = {f["service"]: f for f in self.client.get("/api/demo/faults")}
            assert faults[PAYMENT]["needsRestart"], faults
            return (f"clear refused (409), {incidents[0]['key']} opened in {waited:.0f} s; "
                    "approval refused in Compose mode (409), nothing changed")

        self.check("A stuck service needs a restart; Compose mode never changes it", ["FR-09", "FR-10", "NFR-02"], stuck)
        self.snap("08-stuck-compose", f"#/incidents/{state.get('id', '')}")

        def restart() -> str:
            # What an operator does without Kubernetes: restart the container by hand.
            compose("restart", PAYMENT)
            if "id" not in state:
                return "restarted"
            _, waited = wait_for(
                lambda: self.client.incident(state["id"])["incident"]["status"] == "RESOLVED", 240, "resolution"
            )
            mode = {f["service"]: f for f in self.client.get("/api/demo/faults")}[PAYMENT]["mode"]
            assert mode == "none", mode
            return f"after a manual restart the fault is gone and the incident resolved in {waited:.0f} s"

        self.check("Restarting the stuck service clears it", ["FR-09", "UC-05"], restart)

    def load(self) -> str:
        """NFR-07 under load: 25 concurrent clients polling the dashboard and incident APIs."""
        paths = ["/api/dashboard", "/api/incidents", "/api/services", "/api/audit"]

        def client(n: int) -> list[float]:
            samples = []
            for i in range(12):
                started = time.perf_counter()
                self.client.get(paths[(n + i) % len(paths)])
                samples.append((time.perf_counter() - started) * 1000)
            return samples

        started = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=25) as pool:
            samples = [ms for result in pool.map(client, range(25)) for ms in result]
        elapsed = time.perf_counter() - started
        p95 = statistics.quantiles(samples, n=20)[-1]
        self.run.metrics["load_25_clients"] = {
            "requests": len(samples),
            "p95_ms": round(p95, 1),
            "max_ms": round(max(samples), 1),
            "requests_per_s": round(len(samples) / elapsed, 1),
        }
        assert p95 < 2000, f"p95 {p95:.0f} ms under load"
        return f"{len(samples)} requests from 25 concurrent clients, p95 {p95:.0f} ms, max {max(samples):.0f} ms"

    def telemetry_outage(self) -> None:
        def outage() -> str:
            compose("stop", "prometheus")

            def unknown():
                d = self.client.get("/api/dashboard")
                return (
                    d["pipeline"]["status"] == "UNAVAILABLE"
                    and all(s["health"] == "UNKNOWN" for s in d["services"])
                    and all(rule["value"] is None for rule in d["rules"])
                )

            _, waited = wait_for(unknown, 60, "telemetry UNAVAILABLE and health UNKNOWN")
            self.snap("04-overview-telemetry-unavailable")
            return f"reported UNAVAILABLE / UNKNOWN with no values {waited:.0f} s after Prometheus stopped"

        self.check("Prometheus outage is reported, not hidden", ["NFR-04", "UC-01"], outage)

        def restore() -> str:
            compose("start", "prometheus")

            _, waited = wait_for(self.all_healthy, 180, "telemetry CONNECTED again")
            actions = [event["action"] for event in self.client.get("/api/audit?limit=50")]
            assert "TELEMETRY_UNAVAILABLE" in actions and "TELEMETRY_RESTORED" in actions, actions
            return f"CONNECTED and HEALTHY {waited:.0f} s after restart; outage and restore are audited"

        self.check("Telemetry recovery is detected and audited", ["NFR-04", "NFR-05", "FR-13"], restore)

    def availability_incident(self) -> None:
        state: dict[str, Any] = {}

        def down() -> str:
            compose("stop", CHECKOUT)
            incidents, waited = wait_for(lambda: self.client.active(AVAILABILITY), 120, "an availability incident")
            state["id"] = incidents[0]["id"]
            health = self.client.get("/api/services")[0]["health"]
            assert health == "UNAVAILABLE", health
            self.run.metrics["availability_time_to_detect_s"] = round(waited, 1)
            return f"{incidents[0]['key']} opened {waited:.0f} s after the service stopped; health UNAVAILABLE"

        self.check("Stopped service opens an availability incident", ["FR-02", "FR-06", "UC-01"], down)

        def back() -> str:
            compose("start", CHECKOUT)
            if "id" not in state:
                return "service restarted"
            _, waited = wait_for(
                lambda: self.client.incident(state["id"])["incident"]["status"] == "RESOLVED", 180, "resolution"
            )
            return f"resolved {waited:.0f} s after the service restarted"

        self.check("Availability incident resolves when the service returns", ["UC-05"], back)

    def response_times(self) -> str:
        timings: dict[str, float] = {}
        for path in ["/api/dashboard", "/api/incidents", "/api/services", "/api/audit"]:
            samples = []
            for _ in range(20):
                started = time.perf_counter()
                self.client.get(path)
                samples.append((time.perf_counter() - started) * 1000)
            p95 = statistics.quantiles(samples, n=20)[-1]
            timings[path] = round(p95, 1)
            assert p95 < 2000, f"{path} p95 {p95:.0f} ms"
        self.run.metrics["api_p95_ms"] = timings
        return ", ".join(f"{path} p95 {ms:.0f} ms" for path, ms in timings.items())

    def execute(self) -> Run:
        self.check("Stack starts healthy from docker compose", ["FR-14", "NFR-06", "UC-01"], self.stack_ready)
        self.latency_incident()
        self.error_incident()
        self.probable_source()
        self.stuck_service()
        self.telemetry_outage()
        self.availability_incident()
        self.check("Dashboard and incident APIs answer in under 2 s", ["NFR-07"], self.response_times)
        self.check("APIs stay under 2 s with 25 concurrent clients", ["NFR-07", "NFR-06"], self.load)
        self.snap("05-incidents", "#/incidents")
        self.snap("06-activity", "#/activity")
        self.close()
        return self.run


def write_results(run: Run, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    (out / "results.json").write_text(json.dumps(asdict(run), indent=2))
    passed = sum(c.passed for c in run.checks)
    lines = [
        "## System test (Docker Compose stack)",
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
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--ui", default="http://localhost:3000")
    parser.add_argument("--out", default="e2e-results")
    parser.add_argument("--screenshots", action="store_true")
    args = parser.parse_args()

    out = Path(args.out)
    test = SmokeTest(StackClient(args.api), args.ui, out, args.screenshots)
    try:
        run = test.execute()
    finally:
        for service in (CHECKOUT, PAYMENT):
            try:
                StackClient(args.api).fault("none", service)
            except Exception:
                pass
    write_results(run, out)
    failed = [c for c in run.checks if not c.passed]
    print(f"\n{len(run.checks) - len(failed)}/{len(run.checks)} checks passed", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
