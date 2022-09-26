/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.it.backup;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.zeebe.backup.api.BackupStatus;
import io.camunda.zeebe.backup.api.BackupStatusCode;
import io.camunda.zeebe.backup.common.BackupIdentifierImpl;
import io.camunda.zeebe.backup.s3.S3BackupConfig;
import io.camunda.zeebe.backup.s3.S3BackupStore;
import io.camunda.zeebe.qa.util.actuator.BackupActuator;
import io.camunda.zeebe.qa.util.actuator.BackupActuator.TakeBackupResponse;
import io.camunda.zeebe.qa.util.testcontainers.ContainerLogsDumper;
import io.camunda.zeebe.qa.util.testcontainers.MinioContainer;
import io.camunda.zeebe.qa.util.testcontainers.ZeebeTestContainerDefaults;
import io.camunda.zeebe.test.util.socket.SocketUtil;
import io.zeebe.containers.ZeebeBrokerNode;
import io.zeebe.containers.ZeebeNode;
import io.zeebe.containers.ZeebePort;
import io.zeebe.containers.cluster.ZeebeCluster;
import io.zeebe.containers.engine.ContainerEngine;
import java.time.Duration;
import org.agrona.CloseHelper;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Acceptance tests for the backup management API. Tests here should interact with the backups
 * primarily via the management API, and occasionally assert results on the configured backup store.
 *
 * <p>The tests run against a cluster of 2 brokers and 1 gateway, no embedded gateways, two
 * partitions and replication factor of 1. This allows us to test that requests are correctly fanned
 * out across the gateway, since each broker is guaranteed to be leader of a partition.
 *
 * <p>NOTE: this does not test the consistency of backups, nor that partition leaders correctly
 * maintain consistency via checkpoint records. Other test suites should be set up for this.
 */
@Testcontainers
final class BackupApiIT {
  private static final Network NETWORK = Network.newNetwork();

  private final String bucketName = RandomStringUtils.randomAlphabetic(10).toLowerCase();

  @Container
  private final MinioContainer minio =
      new MinioContainer().withNetwork(NETWORK).withDomain("minio.local", bucketName);

  private final ZeebeCluster cluster =
      ZeebeCluster.builder()
          .withImage(ZeebeTestContainerDefaults.defaultTestImage())
          .withNetwork(NETWORK)
          .withBrokersCount(2)
          .withGatewaysCount(1)
          .withReplicationFactor(1)
          .withPartitionsCount(2)
          .withEmbeddedGateway(false)
          .withBrokerConfig(this::configureBroker)
          .withNodeConfig(this::configureNode)
          .build();

  @RegisterExtension
  @SuppressWarnings("unused")
  final ContainerLogsDumper logsWatcher = new ContainerLogsDumper(cluster::getNodes);

  @Container
  private final ContainerEngine engine =
      ContainerEngine.builder()
          .withDebugReceiverPort(SocketUtil.getNextAddress().getPort())
          .withAutoAcknowledge(true)
          .withCluster(cluster)
          .build();

  private S3BackupStore store;

  @AfterAll
  static void afterAll() {
    CloseHelper.quietCloseAll(NETWORK);
  }

  @BeforeEach
  void beforeEach() {
    final var config =
        S3BackupConfig.from(
            bucketName,
            minio.externalEndpoint(),
            minio.region(),
            minio.accessKey(),
            minio.secretKey());
    store = new S3BackupStore(config);

    try (final var s3Client = S3BackupStore.buildClient(config)) {
      s3Client.createBucket(builder -> builder.bucket(config.bucketName()).build()).join();
    }
  }

  @AfterEach
  void afterEach() {
    CloseHelper.quietCloseAll(() -> store.closeAsync().join());
  }

  @Test
  void shouldTakeBackup() {
    // given
    final var actuator = BackupActuator.of(cluster.getAvailableGateway());
    try (final var client = engine.createClient()) {
      client.newPublishMessageCommand().messageName("name").correlationKey("key").send().join();
    }

    // when
    final var response = actuator.take(1L);

    // then
    assertThat(response).isEqualTo(new TakeBackupResponse(1L));
    Awaitility.await("until a backup exists with the given ID")
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(this::assertBackupCompleteOnAllPartitions);
  }

  private void assertBackupCompleteOnAllPartitions() {
    // TODO: this will be replaced by the status API later
    for (int partitionId = 1; partitionId < 2; partitionId++) {
      assertBackupCompleteForPartition(partitionId);
    }
  }

  private void assertBackupCompleteForPartition(final int partitionId) {
    final var backupId = new BackupIdentifierImpl(0, partitionId, 1);
    final var status = store.getStatus(backupId);

    assertThat(status)
        .succeedsWithin(Duration.ofSeconds(30))
        .extracting(BackupStatus::id, BackupStatus::statusCode)
        .containsExactly(backupId, BackupStatusCode.COMPLETED);
  }

  private void configureBroker(final ZeebeBrokerNode<?> broker) {
    broker
        .withEnv("ZEEBE_BROKER_EXPERIMENTAL_FEATURES_ENABLEBACKUP", "true")
        .withEnv("ZEEBE_BROKER_DATA_BACKUP_STORE", "S3")
        .withEnv("ZEEBE_BROKER_DATA_BACKUP_S3_BUCKETNAME", bucketName)
        .withEnv("ZEEBE_BROKER_DATA_BACKUP_S3_ENDPOINT", minio.internalEndpoint())
        .withEnv("ZEEBE_BROKER_DATA_BACKUP_S3_REGION", minio.region())
        .withEnv("ZEEBE_BROKER_DATA_BACKUP_S3_ACCESSKEY", minio.accessKey())
        .withEnv("ZEEBE_BROKER_DATA_BACKUP_S3_SECRETKEY", minio.secretKey());
  }

  private void configureNode(final ZeebeNode<?> node) {
    node.withEnv("MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE", "*")
        .withEnv("MANAGEMENT_ENDPOINTS_BACKUPS_ENABLED", "true")
        .dependsOn(minio);
    node.addExposedPort(ZeebePort.MONITORING.getPort());
  }
}