## System test (Docker Compose stack)

**19/19 checks passed.**

| Result | Check | Requirements | Time | Detail |
|---|---|---|---|---|
| ✅ | Stack starts healthy from docker compose | FR-14, NFR-06, UC-01 | 4.4 s | checkout-api and payment-api HEALTHY 0 s after the API answered |
| ✅ | Latency fault opens an incident | FR-01, FR-02, FR-03, FR-09, UC-02, UC-06 | 47.7 s | CP-0001 opened 48 s after injection, severity HIGH, p95 1.745 s |
| ✅ | Incident carries evidence, timeline and runbook | FR-05, FR-06, FR-07, FR-08, FR-13, UC-03 | 0.0 s | evidence 1.745 s vs threshold 1.0 s with PromQL; probable source checkout-api itself (payment-api healthy) |
| ✅ | Incident appears within 30 s of window satisfaction | NFR-01 | 0.0 s | opened 0.1 s after the 30 s window was satisfied (limit 30 s) |
| ✅ | Sustained fault stays one incident | FR-04 | 12.0 s | still exactly one ACTIVE latency incident after 12 more seconds of breach |
| ✅ | Extreme latency escalates the same incident | FR-03, FR-04, FR-13 | 10.1 s | same incident escalated to CRITICAL in 10 s (peak 2.17 s) |
| ✅ | Clearing the fault resolves it after the recovery window | UC-05, FR-12, FR-13 | 60.3 s | resolved 60 s after clearing the fault; audit INCIDENT_OPENED → SEVERITY_ESCALATED → INCIDENT_RESOLVED |
| ✅ | HTTP error fault opens an error-rate incident | FR-02, FR-03, FR-09 | 45.1 s | CP-0002 opened 45 s after injection at 21% 5xx, CRITICAL |
| ✅ | Error-rate incident resolves after recovery | UC-05, UC-06 | 65.2 s | resolved 65 s after clearing the fault |
| ✅ | A dependency failure is traced to its probable source | FR-08, UC-03, FR-02, FR-07 | 45.2 s | CP-0003 on checkout-api names payment-api as probable source (CP-0004); detected 45 s after injection |
| ✅ | Fixing the dependency resolves both incidents | UC-05, FR-08 | 60.2 s | both incidents resolved 60 s after payment-api recovered |
| ✅ | A stuck service needs a restart; Compose mode never changes it | FR-09, FR-10, NFR-02 | 55.1 s | clear refused (409), CP-0005 opened in 55 s; approval refused in Compose mode (409), nothing changed |
| ✅ | Restarting the stuck service clears it | FR-09, UC-05 | 50.0 s | after a manual restart the fault is gone and the incident resolved in 48 s |
| ✅ | Prometheus outage is reported, not hidden | NFR-04, UC-01 | 22.0 s | reported UNAVAILABLE / UNKNOWN with no values 18 s after Prometheus stopped |
| ✅ | Telemetry recovery is detected and audited | NFR-04, NFR-05, FR-13 | 12.8 s | CONNECTED and HEALTHY 13 s after restart; outage and restore are audited |
| ✅ | Stopped service opens an availability incident | FR-02, FR-06, UC-01 | 20.7 s | CP-0008 opened 20 s after the service stopped; health UNAVAILABLE |
| ✅ | Availability incident resolves when the service returns | UC-05 | 17.8 s | resolved 18 s after the service restarted |
| ✅ | Dashboard and incident APIs answer in under 2 s | NFR-07 | 0.3 s | /api/dashboard p95 15 ms, /api/incidents p95 6 ms, /api/services p95 4 ms, /api/audit p95 3 ms |
| ✅ | APIs stay under 2 s with 25 concurrent clients | NFR-07, NFR-06 | 0.6 s | 300 requests from 25 concurrent clients, p95 111 ms, max 195 ms |

### Measurements

```json
{
  "latency_time_to_detect_s": 47.6,
  "latency_detection_delay_s": 0.083,
  "latency_time_to_resolve_s": 60.3,
  "errors_time_to_detect_s": 45.1,
  "errors_time_to_resolve_s": 65.2,
  "probable_source_time_to_detect_s": 45.1,
  "availability_time_to_detect_s": 20.0,
  "api_p95_ms": {
    "/api/dashboard": 14.7,
    "/api/incidents": 5.9,
    "/api/services": 3.8,
    "/api/audit": 2.8
  },
  "load_25_clients": {
    "requests": 300,
    "p95_ms": 110.7,
    "max_ms": 195.4,
    "requests_per_s": 472.0
  }
}
```

## System test (Kubernetes mode, kind)

**7/7 checks passed.**

| Result | Check | Requirements | Time | Detail |
|---|---|---|---|---|
| ✅ | Kubernetes mode starts healthy | FR-14, NFR-06, UC-01 | 9.5 s | healthy 5 s after deploy; remediation enabled for namespace cloudpulse-demo only |
| ✅ | Prometheus discovers the demo pods by label | FR-01, NFR-10 | 0.0 s | Prometheus found checkout-api, payment-api (pod payment-api-5cc55c757f-tsbzs) and the API, all up |
| ✅ | The remediation identity is least-privilege | NFR-03 | 1.7 s | 3 permissions allowed and 9 denied, exactly as designed |
| ✅ | A stuck service is detected and traced to its probable source | FR-09, FR-08, UC-03 | 54.8 s | CP-0002 (checkout-api) traced to payment-api in 50 s; clearing refused; Kubernetes left the live-but-stuck pod alone |
| ✅ | Approvals must be confirmed, attributed and in scope | FR-11, NFR-02 | 0.1 s | unconfirmed, anonymous and out-of-scope approvals refused (400); deployment untouched |
| ✅ | An approved restart rolls out and recovery is verified | FR-10, FR-11, FR-12, FR-13, UC-04, UC-05 | 61.6 s | restart of payment-api approved → APPROVED → ROLLING_OUT → VERIFYING → RECOVERED in 57 s; pod payment-api-5cc55c757f-tsbzs replaced by payment-api-6cd9cf8897-ll4kr; payment-api incident RESOLVED |
| ✅ | History survives an API restart and nothing re-executes | NFR-02, NFR-05 | 24.3 s | API replaced; incident, remediation and audit intact from PostgreSQL; nothing re-executed |

### Measurements

```json
{
  "stuck_time_to_detect_s": 50.3,
  "approval_to_recovered_s": 57.1,
  "remediation_states_seen": [
    "APPROVED",
    "ROLLING_OUT",
    "VERIFYING",
    "RECOVERED"
  ]
}
```
