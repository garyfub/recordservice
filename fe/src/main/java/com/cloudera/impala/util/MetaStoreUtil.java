// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.util;

import java.util.List;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;

import com.cloudera.impala.catalog.HdfsTable;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/**
 * Utility methods for interacting with the Hive Metastore.
 */
public class MetaStoreUtil {
  private static final Logger LOG = Logger.getLogger(MetaStoreUtil.class);

  // The default maximum number of partitions to fetch from the Hive metastore in one
  // RPC.
  private final static short DEFAULT_MAX_PARTITIONS_PER_RPC = 1000;

  // The maximum number of partitions to fetch from the metastore in one RPC.
  // Read from the 'hive.metastore.batch.retrieve.table.partition.max' Hive configuration
  // and defaults to DEFAULT_MAX_PARTITION_BATCH_SIZE if the value is not present in the
  // Hive configuration.
  private static short maxPartitionsPerRpc_ = DEFAULT_MAX_PARTITIONS_PER_RPC;

  static {
    // Get the value from the Hive configuration, if present.
    HiveConf hiveConf = new HiveConf(HdfsTable.class);
    String strValue = hiveConf.get(
        HiveConf.ConfVars.METASTORE_BATCH_RETRIEVE_TABLE_PARTITION_MAX.toString());
    if (strValue != null) {
      try {
        maxPartitionsPerRpc_ = Short.parseShort(strValue);
      } catch (NumberFormatException e) {
        LOG.error("Error parsing max partition batch size from HiveConfig: ", e);
      }
    }
    if (maxPartitionsPerRpc_ <= 0) {
      LOG.error(String.format("Invalid value for max partition batch size: %d. Using " +
          "default: %d", maxPartitionsPerRpc_, DEFAULT_MAX_PARTITIONS_PER_RPC));
      maxPartitionsPerRpc_ = DEFAULT_MAX_PARTITIONS_PER_RPC;
    }
  }

  /**
   * Fetches all partitions for a table in batches, with each batch containing at most
   * 'maxPartsPerRpc' partitions. Returns a List containing all fetched Partitions.
   * Will throw a MetaException if existing partitions are dropped while a fetch is in
   * progress. To help protect against this, the operation can be retried if there is
   * a MetaException by setting the "numRetries" parameter.
   * Failures due to thrift exceptions (TExceptions) are not retried because they
   * generally mean the connection is broken or has timed out. The HiveClient supports
   * configuring retires at the connection level so it can be enabled independently.
   */
  public static List<org.apache.hadoop.hive.metastore.api.Partition> fetchAllPartitions(
      HiveMetaStoreClient client, String dbName, String tblName, int numRetries)
      throws MetaException, TException {
    Preconditions.checkArgument(numRetries >= 0);
    int retryAttempt = 0;
    while (true) {
      try {
        // First, get all partition names that currently exist.
        List<String> partNames = client.listPartitionNames(dbName, tblName, (short) -1);
        return MetaStoreUtil.fetchPartitionsByName(client, partNames, dbName, tblName);
      } catch (MetaException e) {
        // Only retry for MetaExceptions, since TExceptions could indicate a broken
        // connection which we can't recover from by retrying.
        if (retryAttempt < numRetries) {
          LOG.error(String.format("Error fetching partitions for table: %s.%s. " +
              "Retry attempt: %d/%d", dbName, tblName, retryAttempt, numRetries), e);
          ++retryAttempt;
          // TODO: Sleep for a bit?
        } else {
          throw e;
        }
      }
    }
  }

  /**
   * Given a List of partition names, fetches the matching Partitions from the HMS
   * in batches. Each batch will contain at most 'maxPartsPerRpc' partitions.
   * Returns a List containing all fetched Partitions.
   * Will throw a MetaException if any partitions in 'partNames' do not exist.
   */
  public static List<org.apache.hadoop.hive.metastore.api.Partition>
      fetchPartitionsByName(HiveMetaStoreClient client, List<String> partNames,
          String dbName, String tblName) throws MetaException, TException {
    LOG.trace(String.format("Fetching %d partitions for: %s.%s using partition " +
        "batch size: %d", partNames.size(), dbName, tblName, maxPartitionsPerRpc_));

    List<org.apache.hadoop.hive.metastore.api.Partition> fetchedPartitions =
        Lists.newArrayList();
    // Fetch the partitions in batches.
    for (int i = 0; i < partNames.size(); i += maxPartitionsPerRpc_) {
      // Get a subset of partition names to fetch.
      List<String> partsToFetch =
          partNames.subList(i, Math.min(i + maxPartitionsPerRpc_, partNames.size()));
      // Fetch these partitions from the metastore.
      fetchedPartitions.addAll(
          client.getPartitionsByNames(dbName, tblName, partsToFetch));
    }
    return fetchedPartitions;
  }

  /**
   * Identical to fetchPartitionsByName() except with retry when there is MetaException.
   */
  public static List<org.apache.hadoop.hive.metastore.api.Partition>
      fetchPartitionsByName(HiveMetaStoreClient client, List<String> partNames,
      String dbName, String tblName, int numRetries) throws TException {
    int retryAttempt = 0;
    while (true) {
      try {
        return MetaStoreUtil.fetchPartitionsByName(client, partNames, dbName, tblName);
      } catch (MetaException e) {
        // Only retry for MetaExceptions, since TExceptions could indicate a broken
        // connection which we can't recover from by retrying.
        if (retryAttempt < numRetries) {
          LOG.error(String.format("Error fetching partitions for table: %s.%s. " +
              "Retry attempt: %d/%d", dbName, tblName, retryAttempt, numRetries), e);
          ++retryAttempt;
          // TODO: Sleep for a bit?
        } else {
          throw e;
        }
      }
    }
  }
}
