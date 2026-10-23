package edu.cloudpulse.demo;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Calls {@code DOWNSTREAM_URL/api/work} with explicit timeouts; disabled when the URL is blank. */
@Component
public class HttpDownstream implements Downstream {

  private final String name;
  private final URI target;
  private final RestClient http;

  public HttpDownstream(
      @Value("${cloudpulse.demo.downstream-url:}") String url,
      @Value("${cloudpulse.demo.downstream-name:payment-api}") String name) {
    this.name = name;
    this.target = url == null || url.isBlank() ? null : URI.create(trimSlash(url) + "/api/work");
    // The read timeout is longer than the slowest fault (3 s), so a slow dependency makes this
    // service slow, as in real systems, instead of turning into an immediate error.
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(Duration.ofSeconds(6));
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public boolean configured() {
    return target != null;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public void call() {
    if (target == null) {
      return;
    }
    try {
      http.get().uri(target).retrieve().toBodilessEntity();
    } catch (RuntimeException e) {
      throw new DownstreamFailure(name + " failed: " + e.getClass().getSimpleName(), e);
    }
  }

  private static String trimSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
