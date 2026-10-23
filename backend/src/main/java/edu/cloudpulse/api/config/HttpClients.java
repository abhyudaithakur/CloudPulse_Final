package edu.cloudpulse.api.config;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Outbound HTTP clients always get explicit timeouts. The midpoint used a bare RestTemplate with
 * no read timeout, so a hung Prometheus could stall the scheduler thread indefinitely.
 */
public final class HttpClients {

  private HttpClients() {}

  public static RestClient restClient(Duration connectTimeout, Duration readTimeout) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(connectTimeout);
    factory.setReadTimeout(readTimeout);
    return RestClient.builder().requestFactory(factory).build();
  }
}
