/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.jaeger;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.jr.ob.JSON;
import com.fasterxml.jackson.jr.stree.JacksonJrsTreeCodec;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("deprecation") // Testing deprecated code
class JaegerIntegrationTest {
  private static final OkHttpClient client = new OkHttpClient();

  private static final int QUERY_PORT = 16686;
  private static final int COLLECTOR_PORT = 14250;
  private static final int HEALTH_PORT = 14269;
  private static final String SERVICE_NAME = "E2E-test";
  private static final String JAEGER_URL = "http://localhost";

  @Container
  public static final GenericContainer<?> jaegerContainer =
      new GenericContainer<>("ghcr.io/open-telemetry/opentelemetry-java/jaeger:1.32")
          .withImagePullPolicy(PullPolicy.alwaysPull())
          .withExposedPorts(COLLECTOR_PORT, QUERY_PORT, HEALTH_PORT)
          .waitingFor(Wait.forHttp("/").forPort(HEALTH_PORT));

  @Test
  void testJaegerIntegration() {
    OpenTelemetry openTelemetry = initOpenTelemetry();
    imitateWork(openTelemetry);
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .until(JaegerIntegrationTest::assertJaegerHaveTrace);
  }

  private static OpenTelemetry initOpenTelemetry() {
    SpanExporter jaegerExporter =
        JaegerGrpcSpanExporter.builder()
            .setEndpoint("http://localhost:" + jaegerContainer.getMappedPort(COLLECTOR_PORT))
            .setTimeout(Duration.ofSeconds(30))
            .build();
    return OpenTelemetrySdk.builder()
        .setTracerProvider(
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(jaegerExporter))
                .setResource(
                    Resource.getDefault().toBuilder()
                        .put(stringKey("service.name"), SERVICE_NAME)
                        .build())
                .build())
        .build();
  }

  private void imitateWork(OpenTelemetry openTelemetry) {
    Span span =
        openTelemetry.getTracer(getClass().getCanonicalName()).spanBuilder("Test span").startSpan();
    span.addEvent("some event");
    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    span.end();
  }

  private static boolean assertJaegerHaveTrace() {
    try {
      String url =
          String.format(
              "%s/api/traces?service=%s",
              String.format(JAEGER_URL + ":%d", jaegerContainer.getMappedPort(QUERY_PORT)),
              SERVICE_NAME);

      Request request =
          new Request.Builder()
              .url(url)
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .build();

      TreeNode json;
      try (Response response = client.newCall(request).execute()) {
        json =
            JSON.builder()
                .treeCodec(new JacksonJrsTreeCodec())
                .build()
                .treeFrom(response.body().byteStream());
      }

      return json.get("data").get(0).get("traceID") != null;
    } catch (Exception e) {
      return false;
    }
  }
}
