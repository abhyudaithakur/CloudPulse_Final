package edu.cloudpulse.api.demo;

import edu.cloudpulse.api.audit.AuditAction;
import edu.cloudpulse.api.audit.AuditResult;
import edu.cloudpulse.api.audit.AuditService;
import edu.cloudpulse.api.demo.DemoServiceClient.FaultRefusedException;
import edu.cloudpulse.api.demo.DemoServiceClient.FaultState;
import edu.cloudpulse.api.domain.MonitoredService;
import edu.cloudpulse.api.domain.ServiceTopology;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demo-only fault controls (UC-06, FR-09). Not part of a production-facing API: they exist so the
 * same failure can be injected and cleared repeatably during tests and presentations.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoFaultController {

  static final List<String> MODES =
      List.of("none", "high-latency", "extreme-latency", "error-storm", "stuck");

  private final DemoServiceClient demo;
  private final AuditService audit;
  private final ServiceTopology topology;

  public DemoFaultController(DemoServiceClient demo, AuditService audit, ServiceTopology topology) {
    this.demo = demo;
    this.audit = audit;
    this.topology = topology;
  }

  /** The current fault of every monitored service. */
  @GetMapping("/faults")
  public List<FaultState> all() {
    return topology.all().stream().map(demo::current).toList();
  }

  @GetMapping("/fault")
  public FaultState current(@RequestParam(required = false) String service) {
    return demo.current(service(service));
  }

  @PostMapping("/fault")
  public FaultState apply(@RequestBody(required = false) FaultRequest request) {
    MonitoredService target = service(request == null ? null : request.service());
    String mode =
        request == null || request.mode() == null
            ? "none"
            : request.mode().trim().toLowerCase(Locale.ROOT);
    if (!MODES.contains(mode)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Unsupported fault mode '" + mode + "'. Use one of " + MODES);
    }
    AuditAction action = mode.equals("none") ? AuditAction.FAULT_CLEARED : AuditAction.FAULT_INJECTED;
    String auditTarget = "service:" + target.name();
    try {
      FaultState state = demo.apply(target, mode);
      audit.record(
          action, AuditService.ACTOR_OPERATOR, auditTarget, null, AuditResult.SUCCESS,
          "Demo fault mode of " + target.name() + " set to " + mode + ".");
      return state;
    } catch (FaultRefusedException e) {
      audit.record(
          action, AuditService.ACTOR_OPERATOR, auditTarget, null, AuditResult.FAILURE,
          "Could not set fault mode " + mode + ": " + e.getMessage());
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    } catch (RuntimeException e) {
      audit.record(
          action, AuditService.ACTOR_OPERATOR, auditTarget, null, AuditResult.FAILURE,
          "Could not set fault mode " + mode + ": " + target.name() + " unreachable.");
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, target.name() + " is unavailable");
    }
  }

  private MonitoredService service(String name) {
    if (name == null || name.isBlank()) {
      return topology.all().get(0);
    }
    return topology
        .find(name.trim())
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Unknown service '" + name + "'. Use one of " + topology.names()));
  }

  /** {@code service} defaults to the first configured service (checkout-api). */
  public record FaultRequest(String service, String mode) {}
}
