package edu.cloudpulse.api.detection;

import edu.cloudpulse.api.domain.RuleDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The configured rules, one per (rule type, service), validated once at startup. Ordered by
 * service, then by rule type, as configured.
 */
public final class RuleCatalog {

  private final Map<String, RuleDefinition> rules = new LinkedHashMap<>();

  public RuleCatalog(List<RuleDefinition> definitions) {
    if (definitions == null || definitions.isEmpty()) {
      throw new IllegalArgumentException("cloudpulse needs at least one rule template and service");
    }
    for (RuleDefinition rule : definitions) {
      if (rules.putIfAbsent(rule.key(), rule) != null) {
        throw new IllegalArgumentException("duplicate rule id " + rule.id() + " for " + rule.serviceName());
      }
    }
  }

  public List<RuleDefinition> all() {
    return List.copyOf(rules.values());
  }

  public Optional<RuleDefinition> find(String serviceName, String ruleId) {
    return Optional.ofNullable(rules.get(serviceName + "/" + ruleId));
  }

  public List<RuleDefinition> forService(String serviceName) {
    return rules.values().stream().filter(r -> r.serviceName().equals(serviceName)).toList();
  }

  public List<String> serviceNames() {
    return rules.values().stream().map(RuleDefinition::serviceName).distinct().toList();
  }
}
