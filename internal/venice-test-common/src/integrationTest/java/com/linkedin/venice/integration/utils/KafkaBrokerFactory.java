package com.linkedin.venice.integration.utils;

import static com.linkedin.venice.integration.utils.ProcessWrapper.DEFAULT_HOST_NAME;

import com.linkedin.venice.ConfigKeys;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.pubsub.PubSubClientsFactory;
import com.linkedin.venice.pubsub.PubSubPositionTypeRegistry;
import com.linkedin.venice.pubsub.adapter.kafka.admin.ApacheKafkaAdminAdapterFactory;
import com.linkedin.venice.pubsub.adapter.kafka.consumer.ApacheKafkaConsumerAdapterFactory;
import com.linkedin.venice.pubsub.adapter.kafka.producer.ApacheKafkaProducerAdapterFactory;
import com.linkedin.venice.utils.SslUtils;
import com.linkedin.venice.utils.SslUtils.VeniceTlsConfiguration;
import com.linkedin.venice.utils.TestMockTime;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.VeniceProperties;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import kafka.metrics.KafkaMetricsReporter;
import kafka.server.KafkaConfig;
import kafka.server.KafkaServer;
import org.apache.kafka.common.utils.SystemTime;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scala.Option;
import scala.collection.JavaConverters;
import scala.collection.Seq;


/**
 * Factory class for creating and configuring embedded Kafka brokers.
 * <p>
 * This class encapsulates the logic for instantiating a Kafka server instance,
 * applying the necessary configuration, and preparing it for use—typically in
 * test or local development environments.
 * <p>
 * It is referenced via the system property {@link ServiceFactory#PUBSUB_BROKER_FACTORY_FQCN}
 * to enable tests to run with Kafka-based PubSub clients.
 * Example usage:
 * <pre>
 *   System.getProperty("pubSubBrokerFactory", "com.linkedin.venice.integration.utils.KafkaBrokerFactory");
 * </pre>
 */
class KafkaBrokerFactory implements PubSubBrokerFactory<KafkaBrokerFactory.KafkaBrokerWrapper> {
  private static final Logger LOGGER = LogManager.getLogger(ServiceFactory.class);
  // Class-level state and APIs
  public static final String SERVICE_NAME = "Kafka";
  private static final int OFFSET_TOPIC_PARTITIONS = 1;
  private static final short OFFSET_TOPIC_REPLICATION_FACTOR = 1;
  private static final boolean LOG_CLEANER_ENABLE = false;
  // anchor for creating clients for this broker
  private static final PubSubClientsFactory KAFKA_CLIENTS_FACTORY = new PubSubClientsFactory(
      new ApacheKafkaProducerAdapterFactory(),
      new ApacheKafkaConsumerAdapterFactory(),
      new ApacheKafkaAdminAdapterFactory());

  /**
   * @return a function which yields a {@link KafkaBrokerWrapper} instance
   */
  @Override
  public StatefulServiceProvider<KafkaBrokerWrapper> generateService(PubSubBrokerConfigs configs) {
    return (String serviceName, File dir) -> {
      int port = TestUtils.getFreePort();
      int sslPort = TestUtils.getFreePort();
      Map<String, Object> configMap = new HashMap<>();

      boolean shouldCloseZkServer = false;
      ZkServerWrapper zkServerWrapper = configs.getZkWrapper();
      // If zkServerWrapper is not provided, create a new one.
      if (zkServerWrapper == null) {
        LOGGER.info("Starting Zookeeper for Kafka...");
        shouldCloseZkServer = true;
        zkServerWrapper = ServiceFactory.getZkServer();
      }

      // Essential configs
      configMap.put(KafkaConfig.ZkConnectProp(), zkServerWrapper.getAddress());
      configMap.put(KafkaConfig.PortProp(), port);
      configMap.put(KafkaConfig.HostNameProp(), DEFAULT_HOST_NAME);
      configMap.put(KafkaConfig.LogDirProp(), dir.getAbsolutePath());
      configMap.put(KafkaConfig.AutoCreateTopicsEnableProp(), false);
      configMap.put(KafkaConfig.DeleteTopicEnableProp(), true);
      configMap.put(KafkaConfig.LogMessageTimestampTypeProp(), "LogAppendTime");
      configMap.put(KafkaConfig.LogMessageFormatVersionProp(), "2.4");

      // The configs below aim to reduce the overhead of the Kafka process:
      configMap.put(KafkaConfig.OffsetsTopicPartitionsProp(), OFFSET_TOPIC_PARTITIONS);
      configMap.put(KafkaConfig.OffsetsTopicReplicationFactorProp(), OFFSET_TOPIC_REPLICATION_FACTOR);
      configMap.put(KafkaConfig.LogCleanerEnableProp(), LOG_CLEANER_ENABLE);

      // Setup ssl related configs for kafka.

      VeniceTlsConfiguration tlsConfiguration = SslUtils.getTlsConfiguration();
      Properties sslConfig =
          KafkaTestUtils.getLocalKafkaBrokerSSlConfig(tlsConfiguration, DEFAULT_HOST_NAME, port, sslPort);

      sslConfig.entrySet().stream().forEach(entry -> configMap.put((String) entry.getKey(), entry.getValue()));
      configs.getAdditionalBrokerConfiguration().forEach((key, value) -> {
        String resolvedValue = value;

        if (value.contains("SASL_SSL")) {
          resolvedValue = resolvedValue.replace("$PORT", String.valueOf(sslPort));
        } else if (value.contains("SASL_PLAINTEXT")) {
          resolvedValue = resolvedValue.replace("$PORT", String.valueOf(port));
        }

        resolvedValue = resolvedValue.replace("$HOSTNAME", DEFAULT_HOST_NAME);
        LOGGER.info("Set custom additional value {}: {}", key, resolvedValue);
        configMap.put(key, resolvedValue);
      });

      KafkaConfig kafkaConfig = new KafkaConfig(configMap, true);
      KafkaServer kafkaServer = KafkaBrokerWrapper.instantiateNewKafkaServer(kafkaConfig, configs.getMockTime());
      LOGGER.info(
          "KafkaBroker for region:{} url: {}:{}",
          configs.getRegionName(),
          kafkaServer.config().hostName(),
          kafkaServer.config().port());
      return new KafkaBrokerWrapper(
          kafkaConfig,
          kafkaServer,
          dir,
          zkServerWrapper,
          configs.getRegionName(),
          configs.getClusterName(),
          shouldCloseZkServer,
          configs.getMockTime(),
          tlsConfiguration,
          sslPort);
    };
  }

  @Override
  public String getServiceName() {
    return SERVICE_NAME;
  }

  @Override
  public PubSubClientsFactory getClientsFactory() {
    return KAFKA_CLIENTS_FACTORY;
  }

  /**
   * This class contains a Kafka Broker, and provides facilities for cleaning up
   * its side effects when we're done using it.
   */
  protected static class KafkaBrokerWrapper extends PubSubBrokerWrapper {
    private static final Logger LOGGER = LogManager.getLogger(KafkaBrokerWrapper.class);
    private final int sslPort;
    // Instance-level state and APIs
    private KafkaServer kafkaServer;
    private final KafkaConfig kafkaConfig;
    private final TestMockTime mockTime;
    private final ZkServerWrapper zkServerWrapper;
    private final boolean shouldCloseZkServer;
    private final VeniceTlsConfiguration tlsConfiguration;
    private final String regionName;
    private final String pubSubClusterName;

    /**
     * The constructor is private because {@link KafkaBrokerFactory#generateService(PubSubBrokerConfigs)} should be
     * the only way to construct a {@link KafkaBrokerWrapper} instance.
     *
     * @param kafkaServer the Kafka instance to wrap
     * @param dataDirectory where Kafka keeps its log
     */
    protected KafkaBrokerWrapper(
        KafkaConfig kafkaConfig,
        KafkaServer kafkaServer,
        File dataDirectory,
        ZkServerWrapper zkServerWrapper,
        String regionName,
        String pubSubClusterName,
        boolean shouldCloseZkServer,
        TestMockTime mockTime,
        VeniceTlsConfiguration tlsConfiguration,
        int sslPort) {
      super(SERVICE_NAME + "-" + regionName, dataDirectory);
      this.kafkaConfig = kafkaConfig;
      this.kafkaServer = kafkaServer;
      this.mockTime = mockTime;
      this.tlsConfiguration = tlsConfiguration;
      this.sslPort = sslPort;
      this.zkServerWrapper = zkServerWrapper;
      this.shouldCloseZkServer = shouldCloseZkServer;
      this.regionName = regionName;
      this.pubSubClusterName = pubSubClusterName;
    }

    public String getZkAddress() {
      return zkServerWrapper.getAddress();
    }

    @Override
    public String getHost() {
      return kafkaServer.config().hostName();
    }

    @Override
    public int getPort() {
      return kafkaServer.config().port();
    }

    @Override
    public int getSslPort() {
      return sslPort;
    }

    @Override
    public VeniceTlsConfiguration getTlsConfiguration() {
      return tlsConfiguration;
    }

    @Override
    public String getAddress() {
      return getHost() + ":" + getPort();
    }

    @Override
    protected void internalStart() {
      Properties properties = System.getProperties();
      if (properties.contains("kafka_mx4jenable")) {
        throw new VeniceException(
            "kafka_mx4jenable should not be set! kafka_mx4jenable = " + properties.getProperty("kafka_mx4jenable"));
      }
      kafkaServer.startup();
      LOGGER.info("Kafka broker listening on port: {} and ssl port: {}", getPort(), getSslPort());
    }

    @Override
    protected void internalStop() {
      kafkaServer.shutdown();
      kafkaServer.awaitShutdown();

      // Close the internally created ZkServerWrapper
      if (shouldCloseZkServer) {
        Utils.closeQuietlyWithErrorLogged(zkServerWrapper);
      }
    }

    @Override
    protected void newProcess() {
      kafkaServer = instantiateNewKafkaServer(kafkaConfig, mockTime);
    }

    @Override
    public String toString() {
      return "KafkaBrokerWrapper{address: '" + getAddress() + "', sslAddress: '" + getSSLAddress() + "'}";
    }

    /**
     * This function encapsulates all the Scala weirdness required to interop with the {@link KafkaServer}.
     */
    private static KafkaServer instantiateNewKafkaServer(KafkaConfig kafkaConfig, TestMockTime mockTime) {
      // We cannot get a kafka.utils.Time out of an Optional<TestMockTime>, even though TestMockTime implements it.
      org.apache.kafka.common.utils.Time time =
          mockTime == null ? SystemTime.SYSTEM : new KafkaMockTimeWrapper(mockTime);
      int port = kafkaConfig.getInt(KafkaConfig.PortProp());
      // Scala's Some (i.e.: the non-empty Optional) needs to be instantiated via Some's object (i.e.: static companion
      // class)
      Option<String> threadNamePrefix = scala.Some$.MODULE$.apply("kafka-broker-port-" + port);
      // This needs to be a Scala List
      Seq<KafkaMetricsReporter> metricsReporterSeq = JavaConverters.asScalaBuffer(new ArrayList<>());
      return new KafkaServer(kafkaConfig, time, threadNamePrefix, metricsReporterSeq);
    }

    @Override
    public PubSubClientsFactory getPubSubClientsFactory() {
      return KAFKA_CLIENTS_FACTORY;
    }

    @Override
    public String getRegionName() {
      return regionName;
    }

    @Override
    public String getPubSubClusterName() {
      return pubSubClusterName;
    }

    @Override
    public Map<String, String> getAdditionalConfig() {
      return Collections.singletonMap(
          ConfigKeys.PUBSUB_TYPE_ID_TO_POSITION_CLASS_NAME_MAP,
          VeniceProperties.mapToString(PubSubPositionTypeRegistry.RESERVED_POSITION_TYPE_ID_TO_CLASS_NAME_MAP));
    }

    @Override
    public PubSubPositionTypeRegistry getPubSubPositionTypeRegistry() {
      return PubSubPositionTypeRegistry.RESERVED_POSITION_TYPE_REGISTRY;
    }
  }
}
