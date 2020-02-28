package com.linkedin.venice.utils;

import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.ControllerResponse;
import com.linkedin.venice.controllerapi.NewStoreResponse;
import com.linkedin.venice.controllerapi.UpdateStoreQueryParams;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.hadoop.KafkaPushJob;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.integration.utils.VeniceMultiClusterWrapper;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.samza.VeniceSystemFactory;
import com.linkedin.venice.schema.vson.VsonAvroSchemaAdapter;
import com.linkedin.venice.schema.vson.VsonAvroSerializer;
import com.linkedin.venice.schema.vson.VsonSchema;
import com.linkedin.venice.writer.VeniceWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.util.Utf8;
import org.apache.commons.io.IOUtils;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.samza.config.MapConfig;
import org.apache.samza.system.OutgoingMessageEnvelope;
import org.apache.samza.system.SystemProducer;
import org.apache.samza.system.SystemStream;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.testng.Assert;

import static com.linkedin.venice.CommonConfigKeys.*;
import static com.linkedin.venice.meta.Version.PushType;
import static com.linkedin.venice.hadoop.KafkaPushJob.*;
import static com.linkedin.venice.samza.VeniceSystemFactory.*;
import static com.linkedin.venice.samza.VeniceSystemFactory.DEPLOYMENT_ID;


public class TestPushUtils {
  public static final String USER_SCHEMA_STRING = "{" +
      "  \"namespace\" : \"example.avro\",  " +
      "  \"type\": \"record\",   " +
      "  \"name\": \"User\",     " +
      "  \"fields\": [           " +
      "       { \"name\": \"id\", \"type\": \"string\"},  " +
      "       { \"name\": \"name\", \"type\": \"string\"},  " +
      "       { \"name\": \"age\", \"type\": \"int\" }" +
      "  ] " +
      " } ";

  public static final String USER_SCHEMA_WITH_A_FLOAT_ARRAY_STRING = "{" +
      "  \"namespace\" : \"example.avro\",  " +
      "  \"type\": \"record\",   " +
      "  \"name\": \"ManyFloats\",     " +
      "  \"fields\": [           " +
      "       { \"name\": \"id\", \"type\": \"string\" },  " +
      "       { \"name\": \"name\", \"type\": {\"type\": \"array\", \"items\": \"float\"} },  " +
      "       { \"name\": \"age\", \"type\": \"int\" }" +
      "  ] " +
      " } ";

  public static final String STRING_SCHEMA = "\"string\"";

  public static File getTempDataDirectory() {
    String tmpDirectory = System.getProperty(TestUtils.TEMP_DIRECTORY_SYSTEM_PROPERTY);
    String directoryName = TestUtils.getUniqueString("Venice-Data");
    File dir = new File(tmpDirectory, directoryName).getAbsoluteFile();
    dir.mkdir();
    dir.deleteOnExit();
    return dir;
  }

  /**
   * This function is used to generate a small avro file with 'user' schema.
   *
   * @param parentDir
   * @return the Schema object for the avro file
   * @throws IOException
   */
  public static Schema writeSimpleAvroFileWithUserSchema(File parentDir) throws IOException {
    return writeSimpleAvroFileWithUserSchema(parentDir, true);
  }

  public static Schema writeSimpleAvroFileWithUserSchema(File parentDir, boolean fileNameWithAvroSuffix)
      throws IOException {
    String fileName;
    if (fileNameWithAvroSuffix) {
      fileName = "simple_user.avro";
    } else {
      fileName = "simple_user";
    }
    return writeAvroFile(parentDir, fileName, USER_SCHEMA_STRING,
        (recordSchema, writer) -> {
          String name = "test_name_";
          for (int i = 1; i <= 100; ++i) {
            GenericRecord user = new GenericData.Record(recordSchema);
            user.put("id", Integer.toString(i));
            user.put("name", name + i);
            user.put("age", i);
            writer.append(user);
          }
        });
  }

  /**
   * This file overrides half of the value in {@link #writeSimpleAvroFileWithUserSchema(File)}
   * and add some new values.
   * It's designed to test incremental push
   */
  public static Schema writeSimpleAvroFileWithUserSchema2(File parentDir) throws IOException {
    return writeAvroFile(parentDir, "simple_user.avro", USER_SCHEMA_STRING,
        (recordSchema, writer) -> {
          String name = "test_name_";
          for (int i = 51; i <= 150; ++i) {
            GenericRecord user = new GenericData.Record(recordSchema);
            user.put("id", Integer.toString(i));
            user.put("name", name + (i * 2));
            user.put("age", i * 2);
            writer.append(user);
          }
        });
  }

  public static Schema writeSimpleAvroFileWithDuplicateKey(File parentDir) throws IOException {
    return writeAvroFile(parentDir, "duplicate_key_user.avro", USER_SCHEMA_STRING,
        (recordSchema, avroFileWriter) -> {
          for (int i = 0; i < 100; i ++) {
            GenericRecord user = new GenericData.Record(recordSchema);
            user.put("id", i %10 == 0 ? "0" : Integer.toString(i)); //"id" is the key
            user.put("name", "test_name" + i);
            user.put("age", i);
            avroFileWriter.append(user);
          }
        });
  }


  public static Schema writeSimpleAvroFileWithCustomSize(File parentDir, int numberOfRecords, int minValueSize, int maxValueSize) throws IOException {
    return writeAvroFile(parentDir, "large_values.avro", USER_SCHEMA_STRING,
        (recordSchema, avroFileWriter) -> {
          int sizeRange = maxValueSize - minValueSize;
          for (int i = 0; i < numberOfRecords; i++) {
            int sizeForThisRecord = minValueSize + sizeRange / numberOfRecords * (i + 1);
            GenericRecord user = new GenericData.Record(recordSchema);
            user.put("id", Integer.toString(i)); //"id" is the key
            char[] chars = new char[sizeForThisRecord];
            Arrays.fill(chars, Integer.toString(i).charAt(0));
            Utf8 utf8Value = new Utf8(new String(chars));
            user.put("name", utf8Value);
            user.put("age", i);
            avroFileWriter.append(user);
          }
        });
  }


  public static Schema writeAvroFileWithManyFloatsAndCustomTotalSize(File parentDir, int numberOfRecords, int minValueSize, int maxValueSize) throws IOException {
    return writeAvroFile(parentDir, "many_floats.avro", USER_SCHEMA_WITH_A_FLOAT_ARRAY_STRING,
        (recordSchema, avroFileWriter) -> {
          int sizeRange = maxValueSize - minValueSize;
          for (int i = 0; i < numberOfRecords; i++) {
            int sizeForThisRecord = minValueSize + sizeRange / numberOfRecords * (i + 1);
            avroFileWriter.append(getRecordWithFloatArray(recordSchema, i, sizeForThisRecord));
          }
        });
  }

  public static GenericRecord getRecordWithFloatArray(Schema recordSchema, int index, int size) {
    GenericRecord user = new GenericData.Record(recordSchema);
    user.put("id", Integer.toString(index)); //"id" is the key
    int numberOfFloats = size / 4;
    List<Float> floatsArray = new ArrayList<>();
    for (int j = 0; j < numberOfFloats; j++) {
      floatsArray.add(RandomGenUtils.getRandomFloat());
    }
    user.put("name", floatsArray);
    user.put("age", index);
    return user;
  }

  private static Schema writeAvroFile(File parentDir, String fileName,
      String RecordSchemaStr, AvroFileWriter fileWriter) throws IOException {
    Schema recordSchema = Schema.parse(RecordSchemaStr);
    File file = new File(parentDir, fileName);

    DatumWriter<GenericRecord> datumWriter = new GenericDatumWriter<>(recordSchema);
    DataFileWriter<GenericRecord> dataFileWriter = new DataFileWriter<>(datumWriter);
    dataFileWriter.create(recordSchema, file);
    fileWriter.write(recordSchema, dataFileWriter);
    dataFileWriter.close();

    return recordSchema;
  }

  public static Pair<Schema, Schema> writeSimpleVsonFile(File parentDir) throws IOException{
    String vsonInteger = "\"int32\"";
    String vsonString = "\"string\"";

    writeVsonFile(vsonInteger, vsonString, parentDir,  "simple_vson_file",
        (keySerializer, valueSerializer, writer) ->{
          for (int i = 0; i < 100; i++) {
            writer.append(new BytesWritable(keySerializer.toBytes(i)),
                new BytesWritable(valueSerializer.toBytes(String.valueOf(i + 100))));
          }
        });
    return new Pair<>(VsonAvroSchemaAdapter.parse(vsonInteger), VsonAvroSchemaAdapter.parse(vsonString));
  }

  public enum testRecordType {
    NEARLINE,OFFLINE;
  }
  public enum testTargetedField {
    WEBSITE_URL,LOGO,INDUSTRY;
  }
  public static Schema writeSchemaWithUnknownFieldIntoAvroFile(File parentDir) throws IOException {
    String schemaWithSymbolDocStr = loadFileAsString("SchemaWithSymbolDoc.avsc");
    Schema schemaWithSymbolDoc = Schema.parse(schemaWithSymbolDocStr);
    File file = new File(parentDir, "schema_with_unknown_field.avro");
    DatumWriter<GenericRecord> datumWriter = new GenericDatumWriter<>(schemaWithSymbolDoc);
    DataFileWriter<GenericRecord> dataFileWriter = new DataFileWriter<>(datumWriter);
    dataFileWriter.create(schemaWithSymbolDoc, file);

    for (int i = 1; i <= 10; ++i) {
      GenericRecord newRecord = new GenericData.Record(schemaWithSymbolDoc);
      GenericRecord keyRecord = new GenericData.Record(schemaWithSymbolDoc.getField("key").schema());
      keyRecord.put("memberId", (long)i);
      if (0 == i % 2) {
        keyRecord.put("source", testRecordType.NEARLINE);
      } else {
        keyRecord.put("source", testRecordType.OFFLINE);
      }

      GenericRecord valueRecord = new GenericData.Record(schemaWithSymbolDoc.getField("value").schema());
      valueRecord.put("priority", i);
      if (0 == i % 3) {
        valueRecord.put("targetedField", testTargetedField.WEBSITE_URL);
      } else if (1 == i % 3) {
        valueRecord.put("targetedField", testTargetedField.LOGO);
      } else {
        valueRecord.put("targetedField", testTargetedField.INDUSTRY);
      }

      newRecord.put("key", keyRecord);
      newRecord.put("value", valueRecord);
      dataFileWriter.append(newRecord);
    }
    dataFileWriter.close();

    /**
     * return a schema without symbolDoc field so that the venice store is created with schema
     * that doesn't contain symbolDoc but the files in HDFS has symbolDoc.
     */
    String schemaWithoutSymbolDocStr = loadFileAsString("SchemaWithoutSymbolDoc.avsc");
    Schema schemaWithoutSymbolDoc = Schema.parse(schemaWithoutSymbolDocStr);
    return schemaWithoutSymbolDoc;
  }

  //write vson byte (int 8) and short (int16) to a file
  public static Pair<Schema, Schema> writeVsonByteAndShort(File parentDir) throws IOException{
    String vsonByte = "\"int8\"";
    String vsonShort = "\"int16\"";

    writeVsonFile(vsonByte, vsonShort, parentDir,  "vson_byteAndShort_file",
        (keySerializer, valueSerializer, writer) ->{
          for (int i = 0; i < 100; i++) {
            writer.append(new BytesWritable(keySerializer.toBytes((byte) i)),
                new BytesWritable(valueSerializer.toBytes((short) (i - 50))));
          }
        });
    return new Pair<>(VsonAvroSchemaAdapter.parse(vsonByte), VsonAvroSchemaAdapter.parse(vsonShort));
  }

  public static Pair<Schema, Schema> writeComplexVsonFile(File parentDir) throws IOException {
    String vsonInteger = "\"int32\"";
    String vsonString = "{\"member_id\":\"int32\", \"score\":\"float32\"}";;

    Map<String, Object> record = new HashMap<>();
    writeVsonFile(vsonInteger, vsonString, parentDir,  "complex_vson-file",
        (keySerializer, valueSerializer, writer) ->{
          for (int i = 0; i < 100; i++) {
            record.put("member_id", i + 100);
            record.put("score", i % 10 != 0 ? (float) i : null); //allow to have optional field
            writer.append(new BytesWritable(keySerializer.toBytes(i)),
                new BytesWritable(valueSerializer.toBytes(record)));
          }
        });
    return new Pair<>(VsonAvroSchemaAdapter.parse(vsonInteger), VsonAvroSchemaAdapter.parse(vsonString));
  }

  public static Pair<Schema, Schema> writeMultiLevelVsonFile(File parentDir) throws IOException {
    String vsonKeyStr = "\"int32\"";
    String vsonValueStr = "{\"level1\":{\"level21\":{\"field1\":\"int32\"}, \"level22\":{\"field2\":\"int32\"}}}";
    Map<String, Object> record = new HashMap<>();
    writeVsonFile(vsonKeyStr, vsonValueStr, parentDir,  "multilevel_vson_file",
        (keySerializer, valueSerializer, writer) ->{
          for (int i = 0; i < 100; i++) {
            Map<String,Object> record21 = new HashMap<>();
            record21.put("field1", i+100);
            Map<String, Object> record22 = new HashMap<>();
            record22.put("field2", i+100);
            Map<String,Object> record1 = new HashMap<>();
            record1.put("level21", record21);
            record1.put("level22", record22);
            record.put("level1", record1);

            writer.append(new BytesWritable(keySerializer.toBytes(i)),
                new BytesWritable(valueSerializer.toBytes(record)));
          }
        });
    return new Pair<>(VsonAvroSchemaAdapter.parse(vsonKeyStr), VsonAvroSchemaAdapter.parse(vsonValueStr));
  }

  public static Pair<VsonSchema, VsonSchema> writeMultiLevelVsonFile2(File parentDir) throws IOException {
    String vsonKeyStr = "\"int32\"";
    String vsonValueStr = "{\"keys\":[{\"type\":\"string\", \"value\":\"string\"}], \"recs\":[{\"member_id\":\"int32\", \"score\":\"float32\"}]}";
    writeVsonFile(vsonKeyStr, vsonValueStr, parentDir,  "multilevel_vson_file2",
        (keySerializer, valueSerializer, writer) ->{
          for (int i = 0; i < 100; i++) {
            //construct value
            Map<String, Object> valueRecord = new HashMap<>();
            List<Map<String, Object>> innerList1 = new ArrayList<>();
            List<Map<String, Object>> innerList2 = new ArrayList<>();
            Map<String, Object> innerMap1 = new HashMap<>();
            Map<String, Object> innerMap2 = new HashMap<>();

            innerMap1.put("type", String.valueOf(i));
            innerMap1.put("value", String.valueOf(i + 100));
            innerList1.add(innerMap1);
            innerMap2.put("member_id", i);
            innerMap2.put("score", (float) i);
            innerList2.add(innerMap2);
            valueRecord.put("keys", innerList1);
            valueRecord.put("recs", innerList2);

            writer.append(new BytesWritable(keySerializer.toBytes(i)),
                new BytesWritable(valueSerializer.toBytes(valueRecord)));
          }
        });
    return new Pair<>(VsonSchema.parse(vsonKeyStr), VsonSchema.parse(vsonValueStr));
  }

  private static Pair<Schema, Schema> writeVsonFile(String keySchemaStr,
      String valueSchemStr,  File parentDir, String fileName, VsonFileWriter fileWriter) throws IOException {
    SequenceFile.Metadata metadata = new SequenceFile.Metadata();
    metadata.set(new Text("key.schema"), new Text(keySchemaStr));
    metadata.set(new Text("value.schema"), new Text(valueSchemStr));

    VsonAvroSerializer keySerializer = VsonAvroSerializer.fromSchemaStr(keySchemaStr);
    VsonAvroSerializer valueSerializer = VsonAvroSerializer.fromSchemaStr(valueSchemStr);

    try(SequenceFile.Writer writer = SequenceFile.createWriter(new Configuration(),
        SequenceFile.Writer.file(new Path(parentDir.toString(), fileName)),
        SequenceFile.Writer.keyClass(BytesWritable.class),
        SequenceFile.Writer.valueClass(BytesWritable.class),
        SequenceFile.Writer.metadata(metadata))) {
      fileWriter.write(keySerializer, valueSerializer, writer);
    }
    return new Pair<>(VsonAvroSchemaAdapter.parse(keySchemaStr), VsonAvroSchemaAdapter.parse(valueSchemStr));
  }

  private interface VsonFileWriter {
    void write(VsonAvroSerializer keySerializer, VsonAvroSerializer valueSerializer, SequenceFile.Writer writer) throws IOException;
  }

  private interface AvroFileWriter {
    void write(Schema recordSchema, DataFileWriter writer) throws IOException;
  }

  public static Properties defaultH2VProps(String veniceUrl, String inputDirPath, String storeName) {
    Properties props = new Properties();
    props.put(KafkaPushJob.VENICE_URL_PROP, veniceUrl);
    props.put(KafkaPushJob.VENICE_STORE_NAME_PROP, storeName);
    props.put(KafkaPushJob.INPUT_PATH_PROP, inputDirPath);
    props.put(KafkaPushJob.KEY_FIELD_PROP, "id");
    props.put(KafkaPushJob.VALUE_FIELD_PROP, "name");
    // No need for a big close timeout in tests. This is just to speed up discovery of certain regressions.
    props.put(VeniceWriter.CLOSE_TIMEOUT_MS, 500);
    props.put(POLL_JOB_STATUS_INTERVAL_MS, 5 * Time.MS_PER_SECOND);
    props.setProperty(KafkaPushJob.SSL_KEY_STORE_PROPERTY_NAME, "test");
    props.setProperty(KafkaPushJob.SSL_TRUST_STORE_PROPERTY_NAME,"test");
    props.setProperty(KafkaPushJob.SSL_KEY_STORE_PASSWORD_PROPERTY_NAME,"test");
    props.setProperty(KafkaPushJob.SSL_KEY_PASSWORD_PROPERTY_NAME,"test");
    props.setProperty(KafkaPushJob.PUSH_JOB_STATUS_UPLOAD_ENABLE, "false");

    return props;
  }

  public static Properties defaultH2VProps(VeniceClusterWrapper veniceCluster, String inputDirPath, String storeName) {
    return defaultH2VProps(veniceCluster.getRandmonVeniceController().getControllerUrl(), inputDirPath, storeName);
  }

  public static Properties sslH2VProps(VeniceClusterWrapper veniceCluster, String inputDirPath, String storeName) {
    Properties props = defaultH2VProps(veniceCluster, inputDirPath, storeName);
    props.putAll(KafkaSSLUtils.getLocalKafkaClientSSLConfig());
    // remove the path for certs and pwd, because we will get them from hadoop user credentials.
    props.remove(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG);
    props.remove(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG);
    props.remove(SslConfigs.SSL_KEY_PASSWORD_CONFIG);
    props.remove(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG);
    return props;
  }

  public static Properties multiClusterH2VProps(VeniceMultiClusterWrapper veniceMultiClusterWrapper, String clusterName, String inputDirPath, String storeName) {
    Properties props = new Properties();
    // Let h2v talk to multiple controllers.
    props.put(KafkaPushJob.VENICE_URL_PROP, veniceMultiClusterWrapper.getControllerConnectString());
    props.put(KafkaPushJob.KAFKA_URL_PROP, veniceMultiClusterWrapper.getKafkaBrokerWrapper().getAddress());
    props.put(KafkaPushJob.VENICE_CLUSTER_NAME_PROP, clusterName);
    props.put(KafkaPushJob.VENICE_STORE_NAME_PROP, storeName);
    props.put(KafkaPushJob.INPUT_PATH_PROP, inputDirPath);
    props.put(KafkaPushJob.KEY_FIELD_PROP, "id");
    props.put(KafkaPushJob.VALUE_FIELD_PROP, "name");
    props.put(VeniceWriter.CLOSE_TIMEOUT_MS, 500);
    props.setProperty(KafkaPushJob.SSL_KEY_STORE_PROPERTY_NAME, "test");
    props.setProperty(KafkaPushJob.SSL_TRUST_STORE_PROPERTY_NAME,"test");
    props.setProperty(KafkaPushJob.SSL_KEY_STORE_PASSWORD_PROPERTY_NAME,"test");
    props.setProperty(KafkaPushJob.SSL_KEY_PASSWORD_PROPERTY_NAME,"test");


    return props;
  }

  public static ControllerClient createStoreForJob(VeniceClusterWrapper veniceCluster, Schema recordSchema, Properties props) {
    return createStoreForJob(veniceCluster.getClusterName(), recordSchema, props);
  }

  public static ControllerClient createStoreForJob(String veniceClusterName, Schema recordSchema, Properties props) {
    String keySchemaStr = recordSchema.getField(props.getProperty(KafkaPushJob.KEY_FIELD_PROP)).schema().toString();
    String valueSchemaStr = recordSchema.getField(props.getProperty(KafkaPushJob.VALUE_FIELD_PROP)).schema().toString();

    return createStoreForJob(veniceClusterName, keySchemaStr, valueSchemaStr, props, false, false, false);
  }

  public static ControllerClient createStoreForJob(VeniceClusterWrapper veniceClusterWrapper,
                                                   String keySchemaStr, String valueSchema, Properties props) {
    return createStoreForJob(veniceClusterWrapper, keySchemaStr, valueSchema, props, false, false);
  }

  public static ControllerClient createStoreForJob(VeniceClusterWrapper veniceCluster,
                                                   String keySchemaStr, String valueSchemaStr, Properties props, boolean isCompressed) {
    return createStoreForJob(veniceCluster, keySchemaStr, valueSchemaStr, props, isCompressed, false);
  }

  public static ControllerClient createStoreForJob(VeniceClusterWrapper veniceCluster,
                                                   String keySchemaStr, String valueSchemaStr, Properties props,
                                                   boolean isCompressed, boolean chunkingEnabled) {
    return createStoreForJob(veniceCluster.getClusterName(), keySchemaStr, valueSchemaStr, props, isCompressed, chunkingEnabled, false);
  }

  public static ControllerClient createStoreForJob(String veniceClusterName,
                                                   String keySchemaStr, String valueSchemaStr, Properties props,
                                                   boolean isCompressed, boolean chunkingEnabled, boolean incrementalPushEnabled) {

    UpdateStoreQueryParams storeParams = new UpdateStoreQueryParams()
        .setStorageQuotaInByte(Store.UNLIMITED_STORAGE_QUOTA)
        .setCompressionStrategy(isCompressed ? CompressionStrategy.GZIP : CompressionStrategy.NO_OP)
        .setBatchGetLimit(2000)
        .setReadQuotaInCU(1000000000)
        .setChunkingEnabled(chunkingEnabled)
        .setIncrementalPushEnabled(incrementalPushEnabled);

    return createStoreForJob(veniceClusterName, keySchemaStr, valueSchemaStr, props, storeParams);
  }

  public static ControllerClient createStoreForJob(String veniceClusterName,
      String keySchemaStr, String valueSchemaStr, Properties props,
      UpdateStoreQueryParams storeParams) {

    String veniceUrl = props.containsKey(VENICE_DISCOVER_URL_PROP) ? props.getProperty(VENICE_DISCOVER_URL_PROP) : props.getProperty(VENICE_URL_PROP);

    ControllerClient controllerClient =
        new ControllerClient(veniceClusterName, veniceUrl);
    NewStoreResponse newStoreResponse = controllerClient.createNewStore(props.getProperty(KafkaPushJob.VENICE_STORE_NAME_PROP),
        "test@linkedin.com", keySchemaStr, valueSchemaStr);

    Assert.assertFalse(newStoreResponse.isError(), "The NewStoreResponse returned an error: " + newStoreResponse.getError());

    ControllerResponse controllerResponse = controllerClient.updateStore(
        props.getProperty(KafkaPushJob.VENICE_STORE_NAME_PROP), storeParams.setStorageQuotaInByte(Store.UNLIMITED_STORAGE_QUOTA));

    Assert.assertFalse(controllerResponse.isError(), "The UpdateStore response returned an error: " + controllerResponse.getError());

    return controllerClient;
  }

  public static void makeStoreHybrid(VeniceClusterWrapper venice, String storeName, long rewindSeconds, long offsetLag) {
    try(ControllerClient controllerClient = new ControllerClient(venice.getClusterName(), venice.getRandomRouterURL())) {
      ControllerResponse response = controllerClient.updateStore(storeName, new UpdateStoreQueryParams()
          .setHybridRewindSeconds(rewindSeconds)
          .setHybridOffsetLagThreshold(offsetLag));
      if (response.isError()) {
        throw new VeniceException(response.getError());
      }
    }
  }

  public static Map<String, String> getSamzaProducerConfig(VeniceClusterWrapper venice, String storeName, PushType type) {
    Map<String, String> samzaConfig = new HashMap<>();
    String configPrefix = SYSTEMS_PREFIX + "venice" + DOT;
    samzaConfig.put(configPrefix + VENICE_PUSH_TYPE, type.toString());
    samzaConfig.put(configPrefix + VENICE_STORE, storeName);
    samzaConfig.put(D2_ZK_HOSTS_PROPERTY, venice.getZk().getAddress());
    samzaConfig.put(VENICE_PARENT_D2_ZK_HOSTS, "invalid_parent_zk_address");
    samzaConfig.put(DEPLOYMENT_ID, TestUtils.getUniqueString("venice-push-id"));
    samzaConfig.put(SSL_ENABLED, "false");
    return samzaConfig;
  }

  public static SystemProducer getSamzaProducer(VeniceClusterWrapper venice, String storeName, PushType type) {
    Map<String, String> samzaConfig = getSamzaProducerConfig(venice, storeName, type);
    VeniceSystemFactory factory = new VeniceSystemFactory();
    SystemProducer veniceProducer = factory.getProducer("venice", new MapConfig(samzaConfig), null);
    veniceProducer.start();
    return veniceProducer;
  }

  /**
   * Generate a streaming record using the provided producer to the specified store
   * key and value schema of the store must both be "string", the record produced is
   * based on the provided recordId
   */
  public static void sendStreamingRecord(SystemProducer producer, String storeName, int recordId) {
    sendStreamingRecord(producer, storeName, Integer.toString(recordId), "stream_" + recordId);
  }

  public static void sendStreamingRecord(SystemProducer producer, String storeName, Object key, Object message) {
    OutgoingMessageEnvelope envelope = new OutgoingMessageEnvelope(
        new SystemStream("venice", storeName), key, message);
    producer.send(storeName, envelope);
  }

  /**
   * Identical to {@link #sendStreamingRecord(SystemProducer, String, int)} except that the value's length is equal
   * to {@param valueSizeInBytes}. The value is composed exclusively of the first digit of the {@param recordId}.
   *
   * @see #sendStreamingRecord(SystemProducer, String, int)
   */
  public static void sendCustomSizeStreamingRecord(SystemProducer producer, String storeName, int recordId, int valueSizeInBytes) {
    char[] chars = new char[valueSizeInBytes];
    Arrays.fill(chars, Integer.toString(recordId).charAt(0));
    sendStreamingRecord(producer, storeName, Integer.toString(recordId), new String(chars));
  }

  public static String loadFileAsString(String fileName) throws IOException {
    return IOUtils.toString(Thread.currentThread().getContextClassLoader().getResourceAsStream(fileName), "utf-8");
  }
}
