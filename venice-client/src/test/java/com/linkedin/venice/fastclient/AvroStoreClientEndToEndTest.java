package com.linkedin.venice.fastclient;

import com.linkedin.r2.transport.common.Client;
import com.linkedin.venice.client.exceptions.VeniceClientException;
import com.linkedin.venice.client.store.AvroGenericStoreClient;
import com.linkedin.venice.client.store.AvroSpecificStoreClient;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.VersionCreationResponse;
import com.linkedin.venice.fastclient.factory.ClientFactory;
import com.linkedin.venice.fastclient.meta.AbstractStoreMetadata;
import com.linkedin.venice.fastclient.meta.StoreMetadata;
import com.linkedin.venice.fastclient.schema.TestValueSchema;
import com.linkedin.venice.helix.HelixReadOnlySchemaRepository;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.integration.utils.VeniceRouterWrapper;
import com.linkedin.venice.meta.Instance;
import com.linkedin.venice.meta.OnlineInstanceFinder;
import com.linkedin.venice.meta.ReadOnlySchemaRepository;
import com.linkedin.venice.meta.ReadOnlyStoreRepository;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.partitioner.DefaultVenicePartitioner;
import com.linkedin.venice.partitioner.VenicePartitioner;
import com.linkedin.venice.serialization.VeniceKafkaSerializer;
import com.linkedin.venice.serialization.avro.VeniceAvroKafkaSerializer;
import com.linkedin.venice.utils.SslUtils;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.writer.VeniceWriter;
import io.tehuti.metrics.MetricsRepository;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.commons.io.IOUtils;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.*;


public class AvroStoreClientEndToEndTest {
  private VeniceClusterWrapper veniceCluster;
  private String storeVersionName;
  private int valueSchemaId;
  private String storeName;

  private VeniceKafkaSerializer keySerializer;
  private VeniceKafkaSerializer valueSerializer;
  private VeniceWriter<Object, Object, Object> veniceWriter;
  private Client r2Client;

  private static final String KEY_SCHEMA_STR = "\"string\"";
  private static final String VALUE_FIELD_NAME = "int_field";
  private static final String VALUE_SCHEMA_STR = "{\n" + "\"type\": \"record\",\n" + "\"name\": \"TestValueSchema\",\n" + "\"namespace\": \"com.linkedin.venice.fastclient.schema\",\n"
      + "\"fields\": [\n" + "  {\"name\": \"" + VALUE_FIELD_NAME + "\", \"type\": \"int\"}]\n" + "}";
  private static final Schema VALUE_SCHEMA = new Schema.Parser().parse(VALUE_SCHEMA_STR);
  private final String keyPrefix = "key_";
  private final int recordCnt = 100;

  @BeforeClass(alwaysRun = true)
  public void setUp() throws Exception {
    Utils.thisIsLocalhost();
    veniceCluster = ServiceFactory.getVeniceCluster(1, 2, 1, 2, 100, true, false);

    r2Client = ClientTestUtils.getR2Client();

    prepareData();
  }

  private void prepareData() throws Exception {
    // Create test store
    VersionCreationResponse creationResponse = veniceCluster.getNewStoreVersion(KEY_SCHEMA_STR, VALUE_SCHEMA_STR);
    storeVersionName = creationResponse.getKafkaTopic();
    storeName = Version.parseStoreFromKafkaTopicName(storeVersionName);
    valueSchemaId = HelixReadOnlySchemaRepository.VALUE_SCHEMA_STARTING_ID;

    // TODO: Make serializers parameterized so we test them all.
    keySerializer = new VeniceAvroKafkaSerializer(KEY_SCHEMA_STR);
    valueSerializer = new VeniceAvroKafkaSerializer(VALUE_SCHEMA_STR);

    veniceWriter = TestUtils.getVeniceWriterFactory(veniceCluster.getKafka().getAddress()).createVeniceWriter(storeVersionName, keySerializer, valueSerializer);
    final int pushVersion = Version.parseVersionFromKafkaTopicName(storeVersionName);
    veniceWriter.broadcastStartOfPush(new HashMap<>());
    // Insert test record and wait synchronously for it to succeed
    for (int i = 0; i < recordCnt; ++i) {
      GenericRecord record = new GenericData.Record(VALUE_SCHEMA);
      record.put(VALUE_FIELD_NAME, i);
      veniceWriter.put(keyPrefix + i, record, valueSchemaId).get();
    }
    // Write end of push message to make node become ONLINE from BOOTSTRAP
    veniceWriter.broadcastEndOfPush(new HashMap<>());

    // Wait for storage node to finish consuming, and new version to be activated
    String controllerUrl = veniceCluster.getAllControllersURLs();
    TestUtils.waitForNonDeterministicCompletion(30, TimeUnit.SECONDS, () -> {
      int currentVersion = ControllerClient.getStore(controllerUrl, veniceCluster.getClusterName(), storeName).getStore().getCurrentVersion();
      return currentVersion == pushVersion;
    });
  }

  @AfterClass(alwaysRun = true)
  public void cleanUp() {
    IOUtils.closeQuietly(veniceCluster);
    IOUtils.closeQuietly(veniceWriter);
    if (r2Client != null) {
      r2Client.shutdown(null);
    }
  }

  private void runTest(ClientConfig.ClientConfigBuilder clientConfigBuilder, Optional<StoreMetadata> metadata)
      throws Exception {
    // always specify a different MetricsRepository to avoid conflict.
    clientConfigBuilder.setMetricsRepository(new MetricsRepository());
    // Test generic store client first
    AvroGenericStoreClient<String, GenericRecord> genericFastClient;
    if (metadata.isPresent()) {
      genericFastClient = ClientFactory.getGenericStoreClient(metadata.get(), clientConfigBuilder.build());
    } else {
      genericFastClient = ClientFactory.getGenericStoreClient(clientConfigBuilder.build());
    }
    for (int i = 0; i < recordCnt; ++i) {
      String key = keyPrefix + i;
      GenericRecord value = genericFastClient.get(key).get();
      assertEquals(value.get(VALUE_FIELD_NAME), new Integer(i));
    }
    genericFastClient.close();
    // Test specific store client
    ClientConfig.ClientConfigBuilder specificClientConfigBuilder = clientConfigBuilder.clone()
        .setSpecificValueClass(TestValueSchema.class)
        .setMetricsRepository(new MetricsRepository()); // To avoid metric registration conflict.
    AvroSpecificStoreClient<String, TestValueSchema> specificFastClient;
    if (metadata.isPresent()) {
      specificFastClient = ClientFactory.getSpecificStoreClient(metadata.get(), specificClientConfigBuilder.build());
    } else {
      specificFastClient = ClientFactory.getSpecificStoreClient(specificClientConfigBuilder.build());
    }
    for (int i = 0; i < recordCnt; ++i) {
      String key = keyPrefix + i;
      TestValueSchema value = specificFastClient.get(key).get();
      assertEquals(value.int_field, i);
    }
    specificFastClient.close();
  }

  @Test
  public void testSingleGetWithoutDualRead() throws Exception {
    VeniceRouterWrapper routerWrapper = veniceCluster.getRandomVeniceRouter();

    ClientConfig.ClientConfigBuilder clientConfigBuilder = new ClientConfig.ClientConfigBuilder<>();
    clientConfigBuilder.setStoreName(storeName);
    clientConfigBuilder.setR2Client(r2Client);
    clientConfigBuilder.setMetricsRepository(new MetricsRepository());
    clientConfigBuilder.setSpeculativeQueryEnabled(true);

    ClientConfig clientConfig = clientConfigBuilder.build();
    StoreMetadata storeMetadata = new RouterBasedStoreMetadata(
        routerWrapper.getMetaDataRepository(),
        routerWrapper.getSchemaRepository(),
        routerWrapper.getOnlineInstanceFinder(),
        storeName,
        clientConfig
    );

    runTest(clientConfigBuilder, Optional.of(storeMetadata));
  }

  @Test
  public void testSingleGetWithoutDualReadWithHelixBasedMetadataImpl() throws Exception {
    // Test generic store client
    ClientConfig.ClientConfigBuilder clientConfigBuilder = new ClientConfig.ClientConfigBuilder<>();
    clientConfigBuilder.setStoreName(storeName);
    clientConfigBuilder.setR2Client(r2Client);
    clientConfigBuilder.setMetricsRepository(new MetricsRepository());
    clientConfigBuilder.setSpeculativeQueryEnabled(true);
    clientConfigBuilder.setVeniceZKAddress(veniceCluster.getZk().getAddress());
    clientConfigBuilder.setClusterName(veniceCluster.getClusterName());

    runTest(clientConfigBuilder, Optional.empty());
  }

  @Test
  public void testSingleGetWithDualRead() throws Exception {
    VeniceRouterWrapper routerWrapper = veniceCluster.getRandomVeniceRouter();

    // Test generic store client
    ClientConfig.ClientConfigBuilder clientConfigBuilder = new ClientConfig.ClientConfigBuilder<>();
    clientConfigBuilder.setStoreName(storeName);
    clientConfigBuilder.setR2Client(r2Client);
    clientConfigBuilder.setMetricsRepository(new MetricsRepository());
    clientConfigBuilder.setSpeculativeQueryEnabled(true);
    clientConfigBuilder.setDualReadEnabled(true);
    AvroGenericStoreClient<String, GenericRecord> genericThinClient = getGenericThinClient();
    clientConfigBuilder.setGenericThinClient(genericThinClient);
    AvroSpecificStoreClient<String, TestValueSchema> specificThinClient = getSpecificThinClient();
    clientConfigBuilder.setSpecificThinClient(specificThinClient);

    ClientConfig clientConfig = clientConfigBuilder.build();
    StoreMetadata storeMetadata = new RouterBasedStoreMetadata(routerWrapper.getMetaDataRepository(), routerWrapper.getSchemaRepository(),
        routerWrapper.getOnlineInstanceFinder(), storeName, clientConfig);
    runTest(clientConfigBuilder, Optional.of(storeMetadata));

    genericThinClient.close();
    specificThinClient.close();
  }

  private AvroGenericStoreClient<String, GenericRecord> getGenericThinClient() {
    return com.linkedin.venice.client.store.ClientFactory.getAndStartGenericAvroClient(
        com.linkedin.venice.client.store.ClientConfig.defaultGenericClientConfig(storeName)
            .setVeniceURL(veniceCluster.getRandomRouterSslURL())
            .setSslEngineComponentFactory(SslUtils.getLocalSslFactory())
    );
  }

  private AvroSpecificStoreClient<String, TestValueSchema> getSpecificThinClient() {
    return com.linkedin.venice.client.store.ClientFactory.getAndStartSpecificAvroClient(
        com.linkedin.venice.client.store.ClientConfig.defaultGenericClientConfig(storeName)
            .setSpecificValueClass(TestValueSchema.class)
            .setVeniceURL(veniceCluster.getRandomRouterSslURL())
            .setSslEngineComponentFactory(SslUtils.getLocalSslFactory())
    );
  }

  private static class RouterBasedStoreMetadata extends AbstractStoreMetadata {
    private final ReadOnlyStoreRepository storeRepository;
    private final ReadOnlySchemaRepository schemaRepository;
    private final OnlineInstanceFinder onlineInstanceFinder;
    private final String storeName;

    public RouterBasedStoreMetadata(ReadOnlyStoreRepository storeRepository,
        ReadOnlySchemaRepository schemaRepository,
        OnlineInstanceFinder onlineInstanceFinder,
        String storeName,
        ClientConfig clientConfig) {
      super(clientConfig);
      this.storeRepository = storeRepository;
      this.schemaRepository = schemaRepository;
      this.onlineInstanceFinder = onlineInstanceFinder;
      this.storeName = storeName;
    }

    @Override
    public int getCurrentStoreVersion() {
      Store store = storeRepository.getStoreOrThrow(storeName);
      return store.getCurrentVersion();
    }

    @Override
    public int getPartitionId(int versionNumber, ByteBuffer key) {
      Store store = storeRepository.getStoreOrThrow(storeName);
      Optional<Version> version = store.getVersion(versionNumber);
      if (!version.isPresent()) {
        throw new VeniceClientException("Version: " + versionNumber + " doesn't exist");
      }
      int partitionCount = version.get().getPartitionCount();
      VenicePartitioner partitioner = new DefaultVenicePartitioner();
      return partitioner.getPartitionId(key, partitionCount);
    }

    @Override
    public List<String> getReplicas(int version, int partitionId) {
      String resource = Version.composeKafkaTopic(storeName, version);
      List<Instance> instances = onlineInstanceFinder.getReadyToServeInstances(resource, partitionId);
      return instances.stream().map(instance -> instance.getUrl(true)).collect(Collectors.toList());
    }

    @Override
    public void start() {
      // do nothing
    }

    @Override
    public Schema getKeySchema() {
      return schemaRepository.getKeySchema(storeName).getSchema();
    }

    @Override
    public Schema getValueSchema(int id) {
      return schemaRepository.getValueSchema(storeName, id).getSchema();
    }

    @Override
    public int getValueSchemaId(Schema schema) {
      return schemaRepository.getValueSchemaId(storeName, schema.toString());
    }

    @Override
    public Schema getLatestValueSchema() {
      return schemaRepository.getLatestValueSchema(storeName).getSchema();
    }

    @Override
    public Integer getLatestValueSchemaId() {
      return schemaRepository.getLatestValueSchema(storeName).getId();
    }

    @Override
    public void close() throws IOException {
      // do nothing
    }
  }

}
