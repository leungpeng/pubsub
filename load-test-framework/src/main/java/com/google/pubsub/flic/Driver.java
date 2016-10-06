// Copyright 2016 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////
package com.google.pubsub.flic;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Timestamp;
import com.google.pubsub.flic.common.LatencyDistribution;
import com.google.pubsub.flic.controllers.Client;
import com.google.pubsub.flic.controllers.Client.ClientType;
import com.google.pubsub.flic.controllers.ClientParams;
import com.google.pubsub.flic.controllers.GCEController;
import org.apache.commons.lang3.math.Fraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.LogManager;

/**
 * Drives the execution of the framework through command line arguments.
 */
class Driver {
  private final static Logger log = LoggerFactory.getLogger(Driver.class);
  @Parameter(
      names = {"--help"},
      help = true
  )
  private boolean help = false;
  @Parameter(
      names = {"--cps_publisher_count"},
      description = "Number of CPS publishers to start."
  )
  private int cpsPublisherCount = 1;
  @Parameter(
      names = {"--cps_subscriber_count"},
      description = "Number of CPS subscribers to start per ."
  )
  private int cpsSubscriberCount = 1;
  @Parameter(
      names = {"--kafka_publisher_count"},
      description = "Number of Kafka publishers to start."
  )
  private int kafkaPublisherCount = 0;
  @Parameter(
      names = {"--kafka_subscriber_count"},
      description = "Number of Kafka subscribers to start."
  )
  private int kafkaSubscriberCount = 0;
  @Parameter(
      names = {"--message_size", "-m"},
      description = "Message size in bytes (only when publishing messages).",
      validateWith = GreaterThanZeroValidator.class
  )
  private int messageSize = 1000;
  @Parameter(
      names = {"--loadtest_seconds"},
      description = "Duration of the load test, in seconds.",
      validateWith = GreaterThanZeroValidator.class
  )
  private int loadtestLengthSeconds = 120;
  @Parameter(
      names = {"--project"},
      required = true,
      description = "Cloud Pub/Sub project name."
  )
  private String project = "";
  @Parameter(
      names = {"--batch_size", "-b"},
      description = "Number of messages to batch per publish request.",
      validateWith = GreaterThanZeroValidator.class
  )
  private int batchSize = 1000;
  @Parameter(
      names = {"--subscriber_fanout"},
      description = "Number of subscription ids to use for each topic. Must be at least 1.",
      validateWith = GreaterThanZeroValidator.class
  )
  private int subscriberFanout = 1;
  @Parameter(
      names = {"--broker"},
      description = "The network address of the Kafka broker."
  )
  private String broker;

  public static void main(String[] args) {
    // Turns off all java.util.logging.
    LogManager.getLogManager().reset();
    Driver driver = new Driver();
    JCommander jCommander = new JCommander(driver, args);
    if (driver.help) {
      jCommander.usage();
      return;
    }
    driver.run();
  }

  private void run() {
    try {
      Map<String, Map<ClientParams, Integer>> clientTypes = ImmutableMap.of(
          "us-central1-a", new HashMap<>());
      Preconditions.checkArgument(
          cpsPublisherCount > 0 ||
              cpsSubscriberCount > 0 ||
              kafkaPublisherCount > 0 ||
              kafkaSubscriberCount > 0
      );
      Preconditions.checkArgument(
          broker != null || (kafkaPublisherCount == 0 && kafkaSubscriberCount == 0));
      for (int i = 0; i < subscriberFanout; ++i) {
        clientTypes.get("us-central1-a").put(new ClientParams(ClientType.CPS_GCLOUD_PUBLISHER, null),
            cpsPublisherCount / subscriberFanout);
        clientTypes.get("us-central1-a").put(new ClientParams(ClientType.CPS_GCLOUD_SUBSCRIBER,
                "gcloud-subscription" + i),
            cpsSubscriberCount / subscriberFanout);
        clientTypes.get("us-central1-a").put(new ClientParams(ClientType.KAFKA_PUBLISHER, null),
            kafkaPublisherCount / subscriberFanout);
        clientTypes.get("us-central1-a").put(new ClientParams(ClientType.KAFKA_SUBSCRIBER, null),
            kafkaSubscriberCount / subscriberFanout);
      }
      Client.messageSize = messageSize;
      Client.requestRate = 1;
      Client.startTime = Timestamp.newBuilder().build();
      Client.loadtestLengthSeconds = loadtestLengthSeconds;
      Client.batchSize = batchSize;
      Client.broker = broker;
      Date startTime = new Date();
      GCEController gceController =
          GCEController.newGCEController(project, clientTypes, Executors.newScheduledThreadPool(500));
      gceController.initialize();
      gceController.startClients();

      // Wait for the load test to finish.
      Map<ClientType, long[]> results = gceController.getResults();
      results.forEach((type, bucketValues) -> {
        log.info("Results for " + type + ":");
        log.info("50%: " + LatencyDistribution.getNthPercentile(bucketValues, Fraction.getFraction(1, 2)));
        log.info("99%: " + LatencyDistribution.getNthPercentile(bucketValues, Fraction.getFraction(99, 100)));
        log.info("99.9%: " + LatencyDistribution.getNthPercentile(bucketValues, Fraction.getFraction(999, 1000)));
      });
      gceController.shutdown(new Exception("Loadtest completed."));
    } catch (Throwable t) {
      log.error("An error occurred...", t);
      System.exit(1);
    }
  }

  /**
   * A validator that makes sure the parameter is an integer that is greater than 0.
   */
  private static class GreaterThanZeroValidator implements IParameterValidator {
    @Override
    public void validate(String name, String value) throws ParameterException {
      try {
        int n = Integer.parseInt(value);
        if (n > 0) return;
        throw new NumberFormatException();
      } catch (NumberFormatException e) {
        throw new ParameterException(
            "Parameter " + name + " should be an int greater than 0 (found " + value + ")");
      }
    }
  }
}
