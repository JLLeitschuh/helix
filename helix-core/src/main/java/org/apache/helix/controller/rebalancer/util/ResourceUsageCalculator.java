package org.apache.helix.controller.rebalancer.util;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.HashMap;
import java.util.Map;

import org.apache.helix.api.rebalancer.constraint.dataprovider.PartitionWeightProvider;
import org.apache.helix.controller.common.ResourcesStateMap;
import org.apache.helix.model.Partition;
import org.apache.helix.model.ResourceAssignment;

public class ResourceUsageCalculator {
  /**
   * A convenient tool for calculating partition capacity usage based on the assignment and resource weight provider.
   *
   * @param resourceAssignment
   * @param weightProvider
   * @return
   */
  public static Map<String, Integer> getResourceUsage(ResourcesStateMap resourceAssignment,
      PartitionWeightProvider weightProvider) {
    Map<String, Integer> newParticipantUsage = new HashMap<>();
    for (String resource : resourceAssignment.resourceSet()) {
      Map<Partition, Map<String, String>> stateMap =
          resourceAssignment.getPartitionStateMap(resource).getStateMap();
      for (Partition partition : stateMap.keySet()) {
        for (String participant : stateMap.get(partition).keySet()) {
          if (!newParticipantUsage.containsKey(participant)) {
            newParticipantUsage.put(participant, 0);
          }
          newParticipantUsage.put(participant, newParticipantUsage.get(participant) + weightProvider
              .getPartitionWeight(resource, partition.getPartitionName()));
        }
      }
    }
    return newParticipantUsage;
  }

  /**
   * Measure baseline divergence between baseline assignment and best possible assignment at
   * replica level. Example as below:
   * baseline =
   * {
   *    resource1={
   *       partition1={
   *          instance1=master,
   *          instance2=slave
   *       },
   *       partition2={
   *          instance2=slave
   *       }
   *    }
   * }
   * bestPossible =
   * {
   *    resource1={
   *       partition1={
   *          instance1=master,  <--- matched
   *          instance3=slave    <--- doesn't match
   *       },
   *       partition2={
   *          instance3=master   <--- doesn't match
   *       }
   *    }
   * }
   * baseline divergence = (doesn't match: 2) / (total(matched + doesn't match): 3) = 2/3 ~= 0.66667
   * If divergence == 1.0, all are different(no match); divergence == 0.0, no difference.
   *
   * @param baseline baseline assignment
   * @param bestPossibleAssignment best possible assignment
   * @return double value range at [0.0, 1.0]
   */
  public static double measureBaselineDivergence(Map<String, ResourceAssignment> baseline,
      Map<String, ResourceAssignment> bestPossibleAssignment) {
    int numMatchedReplicas = 0;
    int numTotalBestPossibleReplicas = 0;

    // 1. Check resource assignment names.
    for (Map.Entry<String, ResourceAssignment> resourceEntry : bestPossibleAssignment.entrySet()) {
      String resourceKey = resourceEntry.getKey();
      if (!baseline.containsKey(resourceKey)) {
        continue;
      }

      // Resource assignment names are matched.
      // 2. check partitions.
      Map<String, Map<String, String>> bestPossiblePartitions =
          resourceEntry.getValue().getRecord().getMapFields();
      Map<String, Map<String, String>> baselinePartitions =
          baseline.get(resourceKey).getRecord().getMapFields();

      for (Map.Entry<String, Map<String, String>> partitionEntry
          : bestPossiblePartitions.entrySet()) {
        String partitionName = partitionEntry.getKey();
        if (!baselinePartitions.containsKey(partitionName)) {
          continue;
        }

        // Partition names are matched.
        // 3. Check replicas.
        Map<String, String> bestPossibleReplicas = partitionEntry.getValue();
        Map<String, String> baselineReplicas = baselinePartitions.get(partitionName);

        for (Map.Entry<String, String> replicaEntry : bestPossibleReplicas.entrySet()) {
          String replicaName = replicaEntry.getKey();
          if (!baselineReplicas.containsKey(replicaName)) {
            continue;
          }

          // Replica names are matched.
          // 4. Check replica values.
          String bestPossibleReplica = replicaEntry.getValue();
          String baselineReplica = baselineReplicas.get(replicaName);
          if (bestPossibleReplica.equals(baselineReplica)) {
            numMatchedReplicas++;
          }
        }

        // Count total best possible replicas.
        numTotalBestPossibleReplicas += bestPossibleReplicas.size();
      }
    }

    return numTotalBestPossibleReplicas == 0 ? 1.0d
        : (1.0d - (double) numMatchedReplicas / (double) numTotalBestPossibleReplicas);
  }

  /**
   * Calculates average partition weight per capacity key for a resource config. Example as below:
   * Input =
   * {
   *   "partition1": {
   *     "capacity1": 20,
   *     "capacity2": 40
   *   },
   *   "partition2": {
   *     "capacity1": 30,
   *     "capacity2": 50
   *   },
   *   "partition3": {
   *     "capacity1": 16,
   *     "capacity2": 30
   *   }
   * }
   *
   * Total weight for key "capacity1" = 20 + 30 + 16 = 66;
   * Total weight for key "capacity2" = 40 + 50 + 30 = 120;
   * Total partitions = 3;
   * Average partition weight for "capacity1" = 66 / 3 = 22;
   * Average partition weight for "capacity2" = 120 / 3 = 40;
   *
   * Output =
   * {
   *   "capacity1": 22,
   *   "capacity2": 40
   * }
   *
   * @param partitionCapacityMap A map of partition capacity:
   *        <PartitionName or DEFAULT_PARTITION_KEY, <Capacity Key, Capacity Number>>
   * @return A map of partition weight: capacity key -> average partition weight
   */
  public static Map<String, Integer> calculateAveragePartitionWeight(
      Map<String, Map<String, Integer>> partitionCapacityMap) {
    // capacity key -> [number of partitions, total weight per capacity key]
    Map<String, PartitionWeightCounterEntry> countPartitionWeightMap = new HashMap<>();

    // Aggregates partition weight for each capacity key.
    partitionCapacityMap.values().forEach(partitionCapacityEntry ->
        partitionCapacityEntry.forEach((capacityKey, weight) -> countPartitionWeightMap
            .computeIfAbsent(capacityKey, counterEntry -> new PartitionWeightCounterEntry())
            .increase(1, weight)));

    // capacity key -> average partition weight
    Map<String, Integer> averagePartitionWeightMap = new HashMap<>();

    // Calculate average partition weight for each capacity key.
    // Per capacity key level:
    // average partition weight = (total partition weight) / (number of partitions)
    for (Map.Entry<String, PartitionWeightCounterEntry> entry
        : countPartitionWeightMap.entrySet()) {
      String capacityKey = entry.getKey();
      PartitionWeightCounterEntry weightEntry = entry.getValue();
      int averageWeight = (int) (weightEntry.getWeight() / weightEntry.getPartitions());
      averagePartitionWeightMap.put(capacityKey, averageWeight);
    }

    return averagePartitionWeightMap;
  }

  /*
   * Represents total number of partitions and total partition weight for a capacity key.
   */
  private static class PartitionWeightCounterEntry {
    private int partitions;
    private long weight;

    private int getPartitions() {
      return partitions;
    }

    private long getWeight() {
      return weight;
    }

    private void increase(int partitions, int weight) {
      this.partitions += partitions;
      this.weight += weight;
    }
  }
}
