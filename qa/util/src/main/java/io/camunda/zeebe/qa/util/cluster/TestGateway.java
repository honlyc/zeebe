/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.qa.util.cluster;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.ZeebeClientBuilder;
import io.camunda.zeebe.gateway.impl.configuration.GatewayCfg;
import io.camunda.zeebe.qa.util.actuator.GatewayHealthActuator;
import io.camunda.zeebe.qa.util.actuator.HealthActuator;
import io.camunda.zeebe.test.util.asserts.TopologyAssert;
import java.time.Duration;
import java.util.function.Consumer;
import org.awaitility.Awaitility;

/**
 * Represents a Zeebe gateway, either standalone or embedded.
 *
 * @param <T> the concrete type of the implementation
 */
public interface TestGateway<T extends TestGateway<T>> extends TestApplication<T> {

  /**
   * Returns the address used by clients to interact with the gateway.
   *
   * <p>You can build your client like this:
   *
   * <pre>@{code
   *   ZeebeClient.newClientBuilder()
   *     .gatewayAddress(gateway.gatewayAddress())
   *     .usePlaintext()
   *     .build();
   * }</pre>
   *
   * @return the gateway address
   */
  default String gatewayAddress() {
    return address(TestZeebePort.GATEWAY);
  }

  /**
   * Returns the health actuator for this gateway. You can use this to check for liveness,
   * readiness, and startup.
   */
  default GatewayHealthActuator gatewayHealth() {
    return GatewayHealthActuator.ofAddress(monitoringAddress());
  }

  @Override
  default HealthActuator healthActuator() {
    return gatewayHealth();
  }

  @Override
  default boolean isGateway() {
    return true;
  }

  /**
   * Allows modifying the gateway configuration. Changes will not take effect until the node is
   * restarted.
   */
  T withGatewayConfig(final Consumer<GatewayCfg> modifier);

  /** Returns the gateway configuration for this node. */
  GatewayCfg gatewayConfig();

  /** Returns a new pre-configured client builder for this gateway */
  default ZeebeClientBuilder newClientBuilder() {
    final var builder = ZeebeClient.newClientBuilder().gatewayAddress(gatewayAddress());
    final var security = gatewayConfig().getSecurity();
    if (security.isEnabled()) {
      builder.caCertificatePath(security.getCertificateChainPath().getAbsolutePath());
    } else {
      builder.usePlaintext();
    }

    return builder;
  }

  /**
   * Blocks until the topology is complete. See {@link TopologyAssert#isComplete(int, int, int)} for
   * semantics.
   *
   * @return itself for chaining
   * @see TopologyAssert#isComplete(int, int, int)
   */
  default T awaitCompleteTopology(
      final int clusterSize,
      final int partitionCount,
      final int replicationFactor,
      final Duration timeout) {
    try (final var client = newClientBuilder().build()) {
      Awaitility.await("until cluster topology is complete")
          .atMost(timeout)
          .untilAsserted(
              () ->
                  TopologyAssert.assertThat(client.newTopologyRequest().send().join())
                      .isComplete(clusterSize, partitionCount, replicationFactor));
    }

    return self();
  }

  /**
   * Convenience method to await complete topology of single node clusters.
   *
   * @return itself for chaining
   */
  default T awaitCompleteTopology() {
    return awaitCompleteTopology(1, 1, 1, Duration.ofSeconds(30));
  }
}
