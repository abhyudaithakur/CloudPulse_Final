# Design decisions

Short records of the choices in CloudPulse that a reviewer is most likely to question. Each says
what was decided, why, and what it costs.

## 1. Deterministic rules instead of anomaly detection

**Decision.** Incidents come from explicit threshold rules (p95 latency, 5xx ratio, reachability)
with sustain and recovery windows.

**Why.** Every incident can be explained in one sentence and reproduced from its evidence. The
demo faults give ground truth, so tests can assert exact outcomes. An ML detector would need
training data the project does not have and would be hard to verify.

**Cost.** Thresholds must be chosen by a person; seasonal or gradual degradation is not detected.

## 2. A violation must be sustained, and recovery must be too

**Decision.** An incident opens only after the rule has been violated continuously for the
sustain window (30 s), and resolves only after it has been within threshold for the recovery
window (10 s). A violation during recovery restarts the recovery window.

**Why.** One slow request should not page anyone, and an incident should not flap between open
and resolved while the rolling window drains.

**Cost.** Detection is at least 30 s behind the fault. The measured end-to-end time is published
by CI.

## 3. Missing telemetry is "unknown", never zero

**Decision.** An empty Prometheus result, a NaN (no requests in the window), an error response or
an unreachable Prometheus is a reading with no value. It resets the windows, never resolves an
incident, and makes service health `UNKNOWN`. The UI shows a dash and hatching, not a number.

**Why.** The midpoint version turned NaN into 0 s, which looks perfectly healthy: if the service
or Prometheus went away, an open incident would have "recovered". RAD NFR-04 forbids inventing or
reusing stale measurements.

**Cost.** After Prometheus restarts, windows start again from fresh samples, which can delay an
incident by up to one sustain window.

## 4. Gaps break windows

**Decision.** Two usable samples more than 20 s apart (`max-sample-gap`) do not count as a
continuous violation.

**Why.** "Sustained for 30 s" is a claim about every moment in those 30 s. If the detector was
paused or telemetry was missing, it has no evidence for the gap.

## 5. Severity only escalates during an incident

**Decision.** Severity is derived from how far the value is past the threshold (High at 1.5×,
Critical at 2× for latency). During an incident it can rise, never fall; the peak value is kept.

**Why.** Severity tells the operator how bad the incident *got*. Silently downgrading a Critical
incident because one sample dipped would hide that, and would make severity flap.

## 6. Deduplication is enforced by the database

**Decision.** Besides the application check, a partial unique index allows at most one `ACTIVE`
incident per rule and service: `CREATE UNIQUE INDEX … ON incidents (rule_id, service_name) WHERE
status = 'ACTIVE'`. A violation is caught and treated as "already open".

**Why.** An application-level check-then-insert is a race once two detector instances exist. The
index makes FR-04 true no matter how many replicas run.

## 7. The audit trail is append-only, also in the database

**Decision.** `AuditRepository` exposes only `save` and reads (it is not a `CrudRepository`), the
entity has no setters, and a PostgreSQL trigger rejects `UPDATE` and `DELETE` on `audit_events`.
Incident changes and their audit events commit in the same transaction.

**Why.** An audit trail that can be edited is not evidence. A shared transaction means there is
never an incident change without its audit record, or the reverse.

## 8. No HTTP call inside a database transaction; every HTTP call has a timeout

**Decision.** The detection cycle queries Prometheus first, then applies each decision in its own
short transaction. All outbound clients have connect and read timeouts (2 s / 3 s).

**Why.** The midpoint held a database connection while waiting on Prometheus, with no read
timeout: a hung Prometheus could stall detection forever.

## 9. The demo service owns the fault state

**Decision.** The API reads the current fault from the demo service instead of keeping its own
copy. The old callback from the demo service to the API was removed.

**Why.** Two copies drifted when only the API restarted. The callback also pointed at a path
that did not exist (`/internal/demo-status` instead of `/api/internal/demo-status`) and failed
silently, and a monitored service should not need to know about its monitor.

## 10. Flyway owns the schema

**Decision.** Tables, constraints, the partial index and the audit trigger are created by
versioned Flyway migrations; Hibernate does not touch the schema (`ddl-auto: none`).

**Why.** `ddl-auto=update` cannot express partial indexes or triggers and gives no reviewable
history of schema changes.

## 11. Fixed, reproducible faults

**Decision.** Error storm fails exactly 3 of every 10 requests, not 30 % at random. Latency faults
are fixed delays.

**Why.** The same demo gives the same signal every time, so CI can assert on it and the live demo
behaves like the rehearsal.

## 12. Probable source from the dependency map and timing, worded as "probable"

**Decision.** The services and who calls whom are configuration. For an incident, a dependency
whose own incident began first (or within 30 s) is named the probable source; with several, the
one furthest downstream wins. If a dependency has no telemetry right now, the answer is
"inconclusive". Otherwise the incident's own service is the source.

**Why.** Failures travel upstream: a broken payment service makes checkout fail too. A rule this
simple can be explained, tested exhaustively and checked by an operator in seconds. Saying
"inconclusive" when part of the picture is missing follows the same principle as decision 3.

**Cost.** It is correlation, not proof: two unrelated problems that start together are linked.
The UI says "probable" and shows the evidence so a person makes the final call.

## 13. Remediation is a fixed runbook, approved by a named person, executed later

**Decision.** The only action is `RESTART_DEPLOYMENT`, for the incident's service or a service it
depends on. Approval requires an operator name and `confirm: true`; it is stored first, and a
scheduler executes it seconds later. Each step (approved, executing, rolling out, verifying,
outcome) is persisted before the next, and the database allows one in-progress remediation per
incident.

**Why.** The dangerous part of automation is the action, so it is narrow, explicit and
attributable. Persisting before acting means a crash can never cause a second restart: an attempt
found mid-call after a restart is marked FAILED and needs a new approval.

**Cost.** A few seconds between approval and action, and only one kind of fix.

## 14. Least privilege enforced by Kubernetes, not only by code

**Decision.** The API's service account may `get` and `patch` exactly two deployments
(`resourceNames`) in `cloudpulse-demo`. It cannot delete, create, read secrets, exec, or touch any
other namespace. The CI Kubernetes test asserts these permissions with `kubectl auth can-i`.

**Why.** Code checks can have bugs; the API server's authorization cannot be bypassed from inside
the pod. If CloudPulse were compromised, the worst it could do is restart two demo services.

**Cost.** Adding a monitored service means adding it to the Role as well as the configuration.

## 15. Verification is the incident resolving, inside a window

**Decision.** After the rollout completes, the remediation watches the incident for 150 s. If it
resolves (through the normal recovery window), the outcome is RECOVERED; if not, NOT_RECOVERED
and the incident stays open. A rollout that does not finish in 180 s is FAILED.

**Why.** "Kubernetes accepted the restart" is not the goal; the service working again is. Reusing
the incident's own recovery rule means remediation success is judged by the same evidence as
everything else.

**Cost.** The outcome takes up to two and a half minutes to be final.

## 16. Two calls to the Kubernetes REST API instead of a client library

**Decision.** The API talks to Kubernetes with the JDK HTTP client: a strategic-merge PATCH (the
same change `kubectl rollout restart` makes) and a GET for rollout status, authenticated with the
mounted service-account token and the cluster CA.

**Why.** Two documented REST calls are easier to read, test against a stub and explain than a
large client library, and they make the exact permissions needed obvious.

**Cost.** Anything beyond these two calls would mean writing more of it by hand.
