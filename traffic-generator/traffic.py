"""Steady synthetic traffic for the demo service.

Sends REQUESTS_PER_SECOND requests to TARGET_URL forever and prints a one-line summary every
SUMMARY_SECONDS, so `docker compose logs traffic-generator` shows what the service is doing
without one log line per request.
"""

import os
import time
import urllib.error
import urllib.request

target = os.getenv("TARGET_URL", "http://localhost:8081/api/work")
rate = max(1, int(os.getenv("REQUESTS_PER_SECOND", "4")))
summary_every = max(1, int(os.getenv("SUMMARY_SECONDS", "10")))
interval = 1 / rate

print(f"CloudPulse demo traffic: {rate} requests/second -> {target}", flush=True)

ok = errors = unreachable = 0
total_ms = 0.0
window_started = time.monotonic()

while True:
    started = time.monotonic()
    try:
        with urllib.request.urlopen(target, timeout=10) as response:
            response.read()
        ok += 1
    except urllib.error.HTTPError:
        errors += 1
    except Exception:  # connection refused, DNS failure, timeout: the service is unreachable
        unreachable += 1
    total_ms += (time.monotonic() - started) * 1000

    if time.monotonic() - window_started >= summary_every:
        sent = ok + errors + unreachable
        avg = total_ms / sent if sent else 0
        print(
            f"sent={sent} ok={ok} http_errors={errors} unreachable={unreachable} avg_ms={avg:.0f}",
            flush=True,
        )
        ok = errors = unreachable = 0
        total_ms = 0.0
        window_started = time.monotonic()

    time.sleep(max(0.0, interval - (time.monotonic() - started)))
