package edu.cloudpulse.api.support;

import edu.cloudpulse.api.support.StubHttpServer.Response;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scriptable Prometheus and demo services for checkout-api and payment-api. Each rule query is
 * answered from the fields of the service it names ({@code application="..."} or {@code
 * job="..."}); a null value means "no data". {@link #prometheusDown} simulates an outage (HTTP
 * 503). Each service's fault endpoint is served under {@code /checkout} and {@code /payment}.
 */
public final class FakeTelemetry {

  private static final Pattern MODE = Pattern.compile("\"mode\"\\s*:\\s*\"([^\"]*)\"");
  private static final Pattern SERVICE =
      Pattern.compile("(?:application|job)=\"([a-z0-9-]+)\"");

  /** One monitored service's current measurements and fault. */
  public static final class ServiceTelemetry {
    final String name;
    public volatile Double latency;
    public volatile Double errorRate;
    public volatile Double up;
    public volatile String faultMode;

    ServiceTelemetry(String name) {
      this.name = name;
      healthy();
    }

    public void healthy() {
      latency = 0.04;
      errorRate = 0.0;
      up = 1.0;
      faultMode = "none";
    }

    /** What a pod restart does to the demo service: every in-memory fault is gone. */
    public void restarted() {
      healthy();
    }

    public boolean stuck() {
      return "stuck".equals(faultMode);
    }
  }

  public final ServiceTelemetry checkout = new ServiceTelemetry("checkout-api");
  public final ServiceTelemetry payment = new ServiceTelemetry("payment-api");
  public volatile boolean prometheusDown;

  private final Map<String, ServiceTelemetry> byName =
      Map.of(checkout.name, checkout, payment.name, payment);

  public void healthy() {
    checkout.healthy();
    payment.healthy();
    prometheusDown = false;
  }

  public ServiceTelemetry service(String name) {
    return byName.get(name);
  }

  public void install(StubHttpServer server) {
    server.route("/api/v1/query", request -> instant(request.query().getOrDefault("query", "")));
    server.route("/api/v1/query_range", request -> range());
    server.route("/checkout/api/fault", request -> fault(checkout, request));
    server.route("/payment/api/fault", request -> fault(payment, request));
  }

  private static Response fault(ServiceTelemetry service, StubHttpServer.Request request) {
    if ("POST".equals(request.method())) {
      Matcher m = MODE.matcher(request.body());
      String mode = m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "none";
      if (service.stuck() && !mode.equals("stuck")) {
        // Same contract as the real demo service: a stuck process cannot be talked out of it.
        return Response.of(
            409, "{\"status\":409,\"detail\":\"" + service.name + " is stuck; restart it\"}");
      }
      service.faultMode = mode;
    }
    return Response.json(
        "{\"service\":\""
            + service.name
            + "\",\"faultMode\":\""
            + service.faultMode
            + "\",\"needsRestart\":"
            + service.stuck()
            + "}");
  }

  private Response instant(String promql) {
    if (prometheusDown) {
      return Response.status(503);
    }
    Matcher m = SERVICE.matcher(promql);
    ServiceTelemetry service = m.find() ? byName.get(m.group(1)) : null;
    Double value;
    if (service == null) {
      value = null;
    } else if (promql.contains("histogram_quantile")) {
      value = service.latency;
    } else if (promql.contains("status=~")) {
      value = service.errorRate;
    } else if (promql.contains("up{")) {
      value = service.up;
    } else {
      value = null;
    }
    String result =
        value == null
            ? "[]"
            : "[{\"metric\":{},\"value\":[1760000000.0,\"" + value + "\"]}]";
    return Response.json(
        "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":" + result + "}}");
  }

  private Response range() {
    if (prometheusDown) {
      return Response.status(503);
    }
    return Response.json(
        "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{},"
            + "\"values\":[[1760000000,\"0.041\"],[1760000005,\"NaN\"]]}]}}");
  }
}
