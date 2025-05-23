package remote.producer.concept.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class DlqKafkaConfig {

    private final KafkaProperties kafkaProperties;

    public DlqKafkaConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Bean
    @Qualifier("dlqKafkaTemplate")
    public KafkaTemplate<String, byte[]> dlqKafkaTemplate() {
        return new KafkaTemplate<>(dlqProducerFactory());
    }

    private ProducerFactory<String, byte[]> dlqProducerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null)); // Use a SslBundles argument in Spring Boot 3.2+
        // Override value serializer for DLQ - we want to send the raw byte array
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        // Key serializer can remain StringSerializer or as configured globally
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        
        // Ensure other critical properties for connecting to Confluent Cloud are present
        // These should be inherited from kafkaProperties.buildProducerProperties()
        // such as bootstrap.servers, security.protocol, sasl.mechanism, sasl.jaas.config

        // For DLQ, acks=all is still good for ensuring the failed message lands in the DLQ topic reliably.
        // Other properties like retries, idempotence, etc., might be less critical for DLQ
        // or you might want different settings. For simplicity, we'll largely inherit.
        // Idempotence is not applicable here as we are not using a specific Avro type for the DLQ.
        props.remove(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG);

        return new DefaultKafkaProducerFactory<>(props);
    }
} 