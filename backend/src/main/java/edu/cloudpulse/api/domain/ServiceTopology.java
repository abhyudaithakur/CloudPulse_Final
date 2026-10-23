package edu.cloudpulse.api.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The monitored services and who calls whom, validated once at startup: names are unique, every
 * dependency is a known service, and there are no cycles (a cycle would make "probable source"
 * meaningless).
 */
public final class ServiceTopology {

  private final Map<String, MonitoredService> services = new LinkedHashMap<>();

  public ServiceTopology(List<MonitoredService> list) {
    if (list == null || list.isEmpty()) {
      throw new IllegalArgumentException("cloudpulse.services must list at least one service");
    }
    for (MonitoredService service : list) {
      if (services.putIfAbsent(service.name(), service) != null) {
        throw new IllegalArgumentException("duplicate service: " + service.name());
      }
    }
    for (MonitoredService service : list) {
      for (String dependency : service.dependsOn()) {
        if (!services.containsKey(dependency)) {
          throw new IllegalArgumentException(
              "service " + service.name() + " depends on unknown service " + dependency);
        }
      }
    }
    for (MonitoredService service : list) {
      if (dependenciesOf(service.name()).contains(service.name())) {
        throw new IllegalArgumentException("dependency cycle through " + service.name());
      }
    }
  }

  public List<MonitoredService> all() {
    return List.copyOf(services.values());
  }

  public List<String> names() {
    return List.copyOf(services.keySet());
  }

  public Optional<MonitoredService> find(String name) {
    return Optional.ofNullable(services.get(name));
  }

  /** Everything {@code name} calls, directly or indirectly, nearest first. */
  public List<String> dependenciesOf(String name) {
    List<String> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    Deque<String> queue = new ArrayDeque<>(directDependencies(name));
    while (!queue.isEmpty()) {
      String next = queue.removeFirst();
      if (seen.add(next)) {
        result.add(next);
        if (next.equals(name)) {
          break; // cycle; reported by the constructor
        }
        queue.addAll(directDependencies(next));
      }
    }
    return result;
  }

  /** Every service that calls {@code name}, directly or indirectly, nearest first. */
  public List<String> dependentsOf(String name) {
    List<String> result = new ArrayList<>();
    for (String candidate : services.keySet()) {
      if (!candidate.equals(name) && dependenciesOf(candidate).contains(name)) {
        result.add(candidate);
      }
    }
    return result;
  }

  private List<String> directDependencies(String name) {
    MonitoredService service = services.get(name);
    return service == null ? List.of() : service.dependsOn();
  }
}
