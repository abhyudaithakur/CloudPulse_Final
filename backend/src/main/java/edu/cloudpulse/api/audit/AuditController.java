package edu.cloudpulse.api.audit;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

  private final AuditService audit;

  public AuditController(AuditService audit) {
    this.audit = audit;
  }

  /** Most recent audit events across incidents, faults and telemetry, newest first. */
  @GetMapping
  public List<AuditEventDto> recent(@RequestParam(defaultValue = "100") int limit) {
    int bounded = Math.max(1, Math.min(limit, 500));
    return audit.recent(bounded).stream().map(AuditEventDto::from).toList();
  }
}
