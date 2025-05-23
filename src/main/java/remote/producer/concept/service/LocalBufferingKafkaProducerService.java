package remote.producer.concept.service;

import com.example.platform.telemetry.AssetReport; // Updated import
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import remote.producer.concept.model.BufferedKafkaMessage;
import remote.producer.concept.repository.BufferedKafkaMessageRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Service
public class LocalBufferingKafkaProducerService {

    private static final Logger logger = LoggerFactory.getLogger(LocalBufferingKafkaProducerService.class);

    private final BufferedKafkaMessageRepository messageRepository;
    private final String defaultTopic;

    public LocalBufferingKafkaProducerService(BufferedKafkaMessageRepository messageRepository,
                                            @Value("${kafka.producer.topic}") String defaultTopic) {
        this.messageRepository = messageRepository;
        this.defaultTopic = defaultTopic;
    }

    /**
     * Buffers a message with AssetReport payload to the local store for later sending to the default topic.
     * @param messageKey The Kafka message key.
     * @param assetReportPayload The AssetReport Avro object. // Updated parameter name and type
     */
    @Transactional
    public void bufferMessage(String messageKey, AssetReport assetReportPayload) { // Updated parameter type
        bufferMessage(defaultTopic, messageKey, assetReportPayload);
    }

    /**
     * Buffers a message with AssetReport payload to the local store for later sending.
     * @param topic The Kafka topic.
     * @param messageKey The Kafka message key.
     * @param assetReportPayload The AssetReport Avro object. // Updated parameter name and type
     */
    @Transactional
    public void bufferMessage(String topic, String messageKey, AssetReport assetReportPayload) { // Updated parameter type
        if (assetReportPayload == null) {
            logger.warn("AssetReport payload is null for topic [{}], key [{}]. Skipping buffering.", topic, messageKey); // Updated log message
            return;
        }
        if (topic == null || topic.isBlank()) {
            logger.warn("Topic is null or blank, using default topic: {}", defaultTopic);
            topic = defaultTopic;
        }
        try {
            byte[] avroPayloadBytes = serializeAssetReport(assetReportPayload); // Updated method call
            BufferedKafkaMessage message = new BufferedKafkaMessage(topic, messageKey, avroPayloadBytes);
            messageRepository.save(message);
            logger.info("Buffered Avro message with key [{}], id [{}] for topic [{}], payload type [AssetReport], size: {} bytes", // Updated log message
                        messageKey, message.getId(), topic, avroPayloadBytes.length);
        } catch (IOException e) {
            logger.error("IOException while serializing Avro payload (AssetReport) for topic [{}], key [{}]: {}", topic, messageKey, e.getMessage(), e); // Updated log message
        } catch (Exception e) {
            logger.error("Error buffering Avro message (AssetReport) for topic [{}], key [{}]: {}", topic, messageKey, e.getMessage(), e); // Updated log message
        }
    }

    private byte[] serializeAssetReport(AssetReport assetReportPayload) throws IOException { // Updated method name and parameter
        SpecificDatumWriter<AssetReport> writer = new SpecificDatumWriter<>(AssetReport.class); // Updated type
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        writer.write(assetReportPayload, encoder);
        encoder.flush();
        out.close();
        return out.toByteArray();
    }
} 