package remote.producer.concept.config;

import com.example.platform.telemetry.AssetReport;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

@Configuration
public class PrimaryKafkaConfig {

    private final KafkaProperties kafkaProperties;

    public PrimaryKafkaConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Bean
    @Primary
    public ProducerFactory<String, AssetReport> primaryProducerFactory() {
        // These properties will include the Avro serializer from application.properties
        Map<String, Object> props = kafkaProperties.buildProducerProperties(null); // Use a SslBundles argument in Spring Boot 3.2+
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    @Primary
    public KafkaTemplate<String, AssetReport> primaryKafkaTemplate() {
        return new KafkaTemplate<>(primaryProducerFactory());
    }
} 