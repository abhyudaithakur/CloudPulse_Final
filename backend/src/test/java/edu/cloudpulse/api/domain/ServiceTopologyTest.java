package edu.cloudpulse.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServiceTopologyTest {

  private static MonitoredService service(String name, String... dependsOn) {
    return new MonitoredService(name, URI.create("http://" + name), List.of(dependsOn), null);
  }

  private final ServiceTopology chain =
      new ServiceTopology(
          List.of(
              service("storefront", "checkout-api"),
              service("checkout-api", "payment-api"),
              service("payment-api")));

  @Test
  @DisplayName("FR-08: dependencies are followed transitively, nearest first")
  void fr08_dependenciesNearestFirst() {
    assertThat(chain.dependenciesOf("storefront")).containsExactly("checkout-api", "payment-api");
    assertThat(chain.dependenciesOf("checkout-api")).containsExactly("payment-api");
    assertThat(chain.dependenciesOf("payment-api")).isEmpty();
  }

  @Test
  @DisplayName("FR-08: dependents are every service that calls this one, directly or not")
  void fr08_dependents() {
    assertThat(chain.dependentsOf("payment-api")).containsExactly("storefront", "checkout-api");
    assertThat(chain.dependentsOf("storefront")).isEmpty();
  }

  @Test
  @DisplayName("NFR-10: a bad dependency map fails at startup with a clear message")
  void nfr10_invalidMapsRejected() {
    assertThatThrownBy(() -> new ServiceTopology(List.of(service("a", "missing"))))
        .hasMessageContaining("unknown service missing");
    assertThatThrownBy(() -> new ServiceTopology(List.of(service("a", "b"), service("b", "a"))))
        .hasMessageContaining("dependency cycle");
    assertThatThrownBy(() -> new ServiceTopology(List.of(service("a"), service("a"))))
        .hasMessageContaining("duplicate service");
    assertThatThrownBy(() -> new ServiceTopology(List.of()))
        .hasMessageContaining("at least one service");
  }

  @Test
  @DisplayName("FR-10: the deployment defaults to the service name")
  void fr10_deploymentDefaultsToName() {
    assertThat(chain.find("payment-api").orElseThrow().deployment()).isEqualTo("payment-api");
  }
}
