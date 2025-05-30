package com.linkedin.venice.pubsub.adapter.kafka.admin;

import com.linkedin.venice.pubsub.PubSubAdminAdapterContext;
import com.linkedin.venice.pubsub.PubSubAdminAdapterFactory;
import com.linkedin.venice.pubsub.PubSubProducerAdapterFactory;
import com.linkedin.venice.pubsub.api.PubSubAdminAdapter;
import java.io.IOException;


/**
 * Implementation of {@link PubSubProducerAdapterFactory} used to create Apache Kafka admin clients.
 *
 * A kafka based admin client created using this factory is for managing and inspecting topics, brokers, configurations and ACLs.
 */

public class ApacheKafkaAdminAdapterFactory extends PubSubAdminAdapterFactory<PubSubAdminAdapter> {
  private static final String NAME = "ApacheKafkaAdmin";

  /**
   * Constructor for ApacheKafkaAdminAdapterFactory used mainly for reflective instantiation.
   */
  public ApacheKafkaAdminAdapterFactory() {
    // no-op
  }

  @Override
  public PubSubAdminAdapter create(PubSubAdminAdapterContext adminAdapterContext) {
    return new ApacheKafkaAdminAdapter(new ApacheKafkaAdminConfig(adminAdapterContext));
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public void close() throws IOException {
  }
}
