package edu.cloudpulse.demo;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code /api/work} is the request the traffic generator sends; Micrometer records it as the
 * {@code http_server_requests_seconds} histogram (with {@code status}) that CloudPulse queries.
 * This service is the single source of truth for its own active fault.
 *
 * <p>One image runs as both services: {@code SERVICE_NAME=checkout-api} with
 * {@code DOWNSTREAM_URL} pointing at {@code SERVICE_NAME=payment-api}. Checkout does its own work,
 * then calls payment; payment's latency adds to checkout's, and a payment failure becomes a 502.
 */
@RestController
@RequestMapping("/api")
public class WorkController {

  private final String service;
  private final Downstream downstream;
  private final AtomicReference<FaultMode> fault = new AtomicReference<>(FaultMode.NONE);
  private final AtomicLong requests = new AtomicLong();

  public WorkController(
      @Value("${cloudpulse.demo.service-name:checkout-api}") String service, Downstream downstream) {
    this.service = service;
    this.downstream = downstream;
  }

  @GetMapping("/work")
  public ResponseEntity<Map<String, Object>> work() throws InterruptedException {
    FaultMode mode = fault.get();
    Thread.sleep(mode.delay().toMillis());
    long n = requests.incrementAndGet();
    if (mode.shouldFail(n)) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(body("error", mode, "injected fault"));
    }
    if (downstream.configured()) {
      try {
        downstream.call();
      } catch (Downstream.DownstreamFailure e) {
        // The dependency failed, so this request fails too: the symptom spreads upstream.
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body("error", mode, e.getMessage()));
      }
    }
    Map<String, Object> ok = body("ok", mode, null);
    ok.put("simulatedDelayMs", mode.delay().toMillis());
    return ResponseEntity.ok(ok);
  }

  @GetMapping("/fault")
  public Map<String, Object> currentFault() {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("service", service);
    state.put("faultMode", fault.get().id());
    state.put("needsRestart", fault.get().needsRestart());
    return state;
  }

  @PostMapping("/fault")
  public Map<String, Object> setFault(@RequestBody(required = false) Map<String, String> body) {
    String requested = body == null ? "none" : body.getOrDefault("mode", "none");
    FaultMode mode =
        FaultMode.fromId(requested)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unsupported fault mode '" + requested + "'. Use one of " + FaultMode.ids()));
    FaultMode current = fault.get();
    if (current.needsRestart() && mode != current) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          service + " is stuck: the process is wedged and only a restart clears it.");
    }
    fault.set(mode);
    return currentFault();
  }

  private Map<String, Object> body(String result, FaultMode mode, String detail) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("service", service);
    map.put("result", result);
    map.put("faultMode", mode.id());
    if (detail != null) {
      map.put("detail", detail);
    }
    map.put("timestamp", Instant.now().toString());
    return map;
  }
}
