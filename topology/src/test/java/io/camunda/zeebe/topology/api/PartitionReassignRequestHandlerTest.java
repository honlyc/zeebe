/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.topology.api;

import static java.lang.Math.max;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.atomix.cluster.MemberId;
import io.atomix.primitive.partition.PartitionId;
import io.camunda.zeebe.scheduler.testing.TestConcurrencyControl;
import io.camunda.zeebe.topology.api.TopologyManagementRequest.ReassignPartitionsRequest;
import io.camunda.zeebe.topology.changes.NoopPartitionChangeExecutor;
import io.camunda.zeebe.topology.changes.NoopTopologyMembershipChangeExecutor;
import io.camunda.zeebe.topology.changes.TopologyChangeAppliersImpl;
import io.camunda.zeebe.topology.state.ClusterTopology;
import io.camunda.zeebe.topology.state.MemberState;
import io.camunda.zeebe.topology.util.RoundRobinPartitionDistributor;
import io.camunda.zeebe.topology.util.TopologyUtil;
import io.camunda.zeebe.util.Either;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.EdgeCasesMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

class PartitionReassignRequestHandlerTest {

  private final RecordingChangeCoordinator coordinator = new RecordingChangeCoordinator();
  private final TopologyManagementRequestsHandler handler =
      new TopologyManagementRequestsHandler(coordinator, new TestConcurrencyControl());

  @BeforeTry
  void clear() {
    coordinator.getLastAppliedOperation().clear();
  }

  @Property(tries = 10)
  void shouldReassignPartitionsWithReplicationFactor1(
      @ForAll @IntRange(min = 1, max = 100) final int partitionCount,
      @ForAll @IntRange(min = 1, max = 100) final int oldClusterSize,
      @ForAll @IntRange(min = 1, max = 100) final int newClusterSize) {
    shouldReassignPartitionsRoundRobin(partitionCount, 1, oldClusterSize, newClusterSize);
  }

  @Property(tries = 10)
  void shouldReassignPartitionsWithReplicationFactor2(
      @ForAll @IntRange(min = 1, max = 100) final int partitionCount,
      @ForAll @IntRange(min = 2, max = 100) final int oldClusterSize,
      @ForAll @IntRange(min = 2, max = 100) final int newClusterSize) {
    shouldReassignPartitionsRoundRobin(partitionCount, 2, oldClusterSize, newClusterSize);
  }

  @Property(tries = 10, shrinking = ShrinkingMode.OFF, edgeCases = EdgeCasesMode.NONE)
  void shouldReassignPartitionsWithReplicationFactor3(
      @ForAll @IntRange(min = 1, max = 100) final int partitionCount,
      @ForAll @IntRange(min = 3, max = 100) final int oldClusterSize,
      @ForAll @IntRange(min = 3, max = 100) final int newClusterSize) {
    shouldReassignPartitionsRoundRobin(partitionCount, 3, oldClusterSize, newClusterSize);
  }

  @Property(tries = 10)
  void shouldReassignPartitionsWithReplicationFactor4(
      @ForAll @IntRange(min = 1, max = 100) final int partitionCount,
      @ForAll @IntRange(min = 4, max = 100) final int oldClusterSize,
      @ForAll @IntRange(min = 4, max = 100) final int newClusterSize) {
    shouldReassignPartitionsRoundRobin(partitionCount, 4, oldClusterSize, newClusterSize);
  }

  @Property
  void shouldFailIfClusterSizeLessThanReplicationFactor3(
      @ForAll @IntRange(min = 0, max = 2) final int newClusterSize) {
    shouldFailIfClusterSizeLessThanReplicationFactor(3, 3, 3, newClusterSize);
  }

  @Property
  void shouldFailIfClusterSizeLessThanReplicationFactor4(
      @ForAll @IntRange(min = 0, max = 3) final int newClusterSize) {
    shouldFailIfClusterSizeLessThanReplicationFactor(12, 4, 6, newClusterSize);
  }

  void shouldFailIfClusterSizeLessThanReplicationFactor(
      final int partitionCount,
      final int replicationFactor,
      final int oldClusterSize,
      final int newClusterSize) {
    // given
    final var oldDistribution =
        new RoundRobinPartitionDistributor()
            .distributePartitions(
                getClusterMembers(oldClusterSize),
                getSortedPartitionIds(partitionCount),
                replicationFactor);
    var oldClusterTopology = TopologyUtil.getClusterTopologyFrom(oldDistribution);
    for (int i = 0; i < max(oldClusterSize, newClusterSize); i++) {
      oldClusterTopology =
          oldClusterTopology.updateMember(
              MemberId.from(Integer.toString(i)),
              currentState ->
                  Objects.requireNonNullElseGet(
                      currentState, () -> MemberState.initializeAsActive(Map.of())));
    }
    coordinator.setCurrentTopology(oldClusterTopology);
    //  when
    final var applyFuture =
        handler.reassignPartitions(
            new ReassignPartitionsRequest(getClusterMembers(newClusterSize)));

    // then
    assertThat(applyFuture)
        .failsWithin(Duration.ofMillis(100))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(IllegalArgumentException.class);
  }

  void shouldReassignPartitionsRoundRobin(
      final int partitionCount,
      final int replicationFactor,
      final int oldClusterSize,
      final int newClusterSize) {
    // given
    final var expectedNewDistribution =
        new RoundRobinPartitionDistributor()
            .distributePartitions(
                getClusterMembers(newClusterSize),
                getSortedPartitionIds(partitionCount),
                replicationFactor);

    final var oldDistribution =
        new RoundRobinPartitionDistributor()
            .distributePartitions(
                getClusterMembers(oldClusterSize),
                getSortedPartitionIds(partitionCount),
                replicationFactor);
    var oldClusterTopology = TopologyUtil.getClusterTopologyFrom(oldDistribution);
    for (int i = 0; i < max(oldClusterSize, newClusterSize); i++) {
      oldClusterTopology =
          oldClusterTopology.updateMember(
              MemberId.from(Integer.toString(i)),
              currentState ->
                  Objects.requireNonNullElseGet(
                      currentState, () -> MemberState.initializeAsActive(Map.of())));
    }
    coordinator.setCurrentTopology(oldClusterTopology);

    // when
    handler
        .reassignPartitions(new ReassignPartitionsRequest(getClusterMembers(newClusterSize)))
        .join();

    // apply operations to generate new topology
    final var topologyChangeSimulator =
        new TopologyChangeAppliersImpl(
            new NoopPartitionChangeExecutor(), new NoopTopologyMembershipChangeExecutor());
    ClusterTopology newTopology = oldClusterTopology;
    if (!coordinator.getLastAppliedOperation().isEmpty()) {
      newTopology = oldClusterTopology.startTopologyChange(coordinator.getLastAppliedOperation());
    }
    while (newTopology.hasPendingChanges()) {
      final var operation = newTopology.changes().pendingOperations().get(0);
      final var applier = topologyChangeSimulator.getApplier(operation);
      final Either<Exception, UnaryOperator<MemberState>> init = applier.init(newTopology);
      if (init.isLeft()) {
        fail("fail");
      }
      newTopology = newTopology.updateMember(operation.memberId(), init.get());
      newTopology = newTopology.advanceTopologyChange(operation.memberId(), applier.apply().join());
    }

    // then
    final var newDistribution = TopologyUtil.getPartitionDistributionFrom(newTopology, "temp");
    assertThat(newDistribution).isEqualTo(expectedNewDistribution);
  }

  private List<PartitionId> getSortedPartitionIds(final int partitionCount) {
    return IntStream.rangeClosed(1, partitionCount)
        .mapToObj(id -> PartitionId.from("temp", id))
        .collect(Collectors.toList());
  }

  private Set<MemberId> getClusterMembers(final int newClusterSize) {
    return IntStream.range(0, newClusterSize)
        .mapToObj(Integer::toString)
        .map(MemberId::from)
        .collect(Collectors.toSet());
  }
}
