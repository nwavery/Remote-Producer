package remote.producer.concept.service;

import com.example.platform.telemetry.AssetReport;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import remote.producer.concept.model.BufferedKafkaMessage;
import remote.producer.concept.repository.BufferedKafkaMessageRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class KafkaMessageSenderService {

    private static final Logger logger = LoggerFactory.getLogger(KafkaMessageSenderService.class);

    private final BufferedKafkaMessageRepository messageRepository;
    private final KafkaTemplate<String, AssetReport> kafkaTemplate;
    private final KafkaTemplate<String, byte[]> dlqKafkaTemplate;
    private final KafkaProperties kafkaProperties;
    private final int batchSize;
    private final int maxSendAttempts;
    private boolean dlqEnabled;
    private final String dlqTopic;

    // Connectivity Check Properties
    private final boolean connectivityCheckEnabled;
    private final String connectivityCheckTargetHost;
    private final int connectivityCheckTargetPort;
    private final int connectivityCheckTimeoutMs;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final double backoffMultiplier;

    // Connectivity State
    private volatile boolean assumedConnectivityOk = true;
    private volatile long currentBackoffMs;
    private volatile long nextConnectivityCheckAttemptTimestamp = 0;
    private String effectiveConnectivityCheckHost;
    private int effectiveConnectivityCheckPort;

    public KafkaMessageSenderService(BufferedKafkaMessageRepository messageRepository,
                                     KafkaTemplate<String, AssetReport> kafkaTemplate,
                                     @Qualifier("dlqKafkaTemplate") KafkaTemplate<String, byte[]> dlqKafkaTemplate,
                                     KafkaProperties kafkaProperties,
                                     @Value("${app.messaging.buffer.batch-size:50}") int batchSize,
                                     @Value("${app.messaging.buffer.max-send-attempts:10}") int maxSendAttempts,
                                     @Value("${app.messaging.dlq.enabled:true}") boolean dlqEnabled,
                                     @Value("${app.messaging.dlq.topic:}") String dlqTopic,
                                     @Value("${app.connectivity.check.enabled:true}") boolean connectivityCheckEnabled,
                                     @Value("${app.connectivity.check.target.host:}") String connectivityCheckTargetHost,
                                     @Value("${app.connectivity.check.target.port:0}") int connectivityCheckTargetPort,
                                     @Value("${app.connectivity.check.timeout.ms:5000}") int connectivityCheckTimeoutMs,
                                     @Value("${app.connectivity.initial.backoff.ms:60000}") long initialBackoffMs,
                                     @Value("${app.connectivity.max.backoff.ms:1800000}") long maxBackoffMs,
                                     @Value("${app.connectivity.backoff.multiplier:2.0}") double backoffMultiplier) {
        this.messageRepository = messageRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.dlqKafkaTemplate = dlqKafkaTemplate;
        this.kafkaProperties = kafkaProperties;
        this.batchSize = batchSize;
        this.maxSendAttempts = maxSendAttempts;
        this.dlqEnabled = dlqEnabled;
        this.dlqTopic = dlqTopic;

        this.connectivityCheckEnabled = connectivityCheckEnabled;
        this.connectivityCheckTargetHost = connectivityCheckTargetHost;
        this.connectivityCheckTargetPort = connectivityCheckTargetPort;
        this.connectivityCheckTimeoutMs = connectivityCheckTimeoutMs;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.backoffMultiplier = backoffMultiplier;
        this.currentBackoffMs = this.initialBackoffMs;

        if (this.dlqEnabled && (this.dlqTopic == null || this.dlqTopic.isBlank())) {
            logger.warn("DLQ is enabled but app.messaging.dlq.topic is not configured. DLQ publishing will be skipped.");
            this.dlqEnabled = false;
        }
        initializeConnectivityCheckTarget();
    }

    private void initializeConnectivityCheckTarget() {
        if (connectivityCheckEnabled) {
            if (connectivityCheckTargetHost != null && !connectivityCheckTargetHost.isBlank() && connectivityCheckTargetPort > 0) {
                this.effectiveConnectivityCheckHost = connectivityCheckTargetHost;
                this.effectiveConnectivityCheckPort = connectivityCheckTargetPort;
            } else {
                List<String> bootstrapServers = kafkaProperties.getBootstrapServers();
                if (bootstrapServers != null && !bootstrapServers.isEmpty()) {
                    String firstServer = bootstrapServers.get(0);
                    String[] parts = firstServer.split(":");
                    if (parts.length == 2) {
                        this.effectiveConnectivityCheckHost = parts[0];
                        try {
                            this.effectiveConnectivityCheckPort = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException e) {
                            logger.error("Failed to parse port from Kafka bootstrap server: {}. Connectivity check might be unreliable.", firstServer, e);
                            this.effectiveConnectivityCheckHost = null; // Disable if parsing fails
                        }
                    } else {
                        logger.warn("Could not parse host:port from Kafka bootstrap server: {}. Connectivity check will be disabled.", firstServer);
                        this.effectiveConnectivityCheckHost = null; // Disable if no suitable host:port
                    }
                } else {
                    logger.warn("No Kafka bootstrap servers configured. Connectivity check will be disabled.");
                    this.effectiveConnectivityCheckHost = null; // Disable if no bootstrap servers
                }
            }
            if (this.effectiveConnectivityCheckHost != null) {
                 logger.info("Connectivity check enabled. Target: {}:{}. Timeout: {}ms. Initial backoff: {}ms, Max backoff: {}ms, Multiplier: {}.",
                    effectiveConnectivityCheckHost, effectiveConnectivityCheckPort, connectivityCheckTimeoutMs, initialBackoffMs, maxBackoffMs, backoffMultiplier);
            } else {
                logger.warn("Connectivity check was enabled but no valid target could be determined. It will be effectively disabled.");
            }
        } else {
            logger.info("Connectivity check is disabled.");
        }
    }

    private boolean isConnectivityAvailable() {
        if (!connectivityCheckEnabled || effectiveConnectivityCheckHost == null) {
            // If check is disabled or target is not set, assume connectivity for sending.
            // The Kafka client's own timeouts will then be the only gate.
            return true; 
        }

        if (!assumedConnectivityOk && System.currentTimeMillis() < nextConnectivityCheckAttemptTimestamp) {
            logger.debug("Currently in connectivity backoff period. Next check not due yet.");
            return false; // Still in backoff
        }

        logger.debug("Performing connectivity check to {}:{} with timeout {}ms", 
                     effectiveConnectivityCheckHost, effectiveConnectivityCheckPort, connectivityCheckTimeoutMs);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(effectiveConnectivityCheckHost, effectiveConnectivityCheckPort), connectivityCheckTimeoutMs);
            if (!assumedConnectivityOk) { // Only log if state changes to OK
                logger.info("Connectivity to {}:{} restored.", effectiveConnectivityCheckHost, effectiveConnectivityCheckPort);
            }
            assumedConnectivityOk = true;
            currentBackoffMs = initialBackoffMs; // Reset backoff on success
            return true;
        } catch (IOException e) {
            if (assumedConnectivityOk) { // Only log if state changes to NOT OK
                logger.warn("Connectivity check to {}:{} failed: {}. Starting backoff.", 
                            effectiveConnectivityCheckHost, effectiveConnectivityCheckPort, e.getMessage());
            }
            assumedConnectivityOk = false;
            nextConnectivityCheckAttemptTimestamp = System.currentTimeMillis() + currentBackoffMs;
            logger.warn("Connectivity unavailable. Current backoff: {}ms. Next check scheduled around: {}", 
                        currentBackoffMs, LocalDateTime.now().plusNanos(currentBackoffMs * 1_000_000L));
            currentBackoffMs = Math.min((long)(currentBackoffMs * backoffMultiplier), maxBackoffMs);
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${app.messaging.buffer.send-interval-ms:60000}")
    @Transactional
    public void processPendingMessages() {
        if (connectivityCheckEnabled && !isConnectivityAvailable()) {
            logger.debug("Connectivity check failed or in backoff. Skipping message processing cycle.");
            return;
        }

        logger.debug("Connectivity OK (or check disabled). Checking for pending messages to send...");
        Pageable pageable = PageRequest.of(0, batchSize);
        List<BufferedKafkaMessage> messagesToSend = messageRepository.findByStatus(remote.producer.concept.model.MessageStatus.PENDING, pageable);

        if (messagesToSend.isEmpty()) {
            logger.debug("No pending messages found.");
            return;
        }

        logger.info("Found {} pending Avro messages (AssetReport) to send. Attempting to send batch.", messagesToSend.size());

        for (BufferedKafkaMessage message : messagesToSend) {
            // Increment attempt count ONLY if we are actually going to try sending to Kafka
            // The decision to deserialize and send is now dependent on connectivity.
            initiateKafkaSendAndHandleOutcome(message);
        }
    }

    private void initiateKafkaSendAndHandleOutcome(BufferedKafkaMessage message) {
        // Save message state (attempt count, last attempt time) BEFORE the async send call
        message.setLastAttemptAt(LocalDateTime.now());
        message.setAttemptCount(message.getAttemptCount() + 1);
        try {
            messageRepository.save(message);
        } catch (Exception e) {
            logger.error("Critical error: Failed to save updated attempt count for message id {} BEFORE Kafka send: {}. Send will be aborted for this message to avoid potential issues.", message.getId(), e.getMessage(), e);
            // If we can't even save the attempt count, proceeding to send is risky.
            // The message remains in its current state in DB and will be picked up again.
            return; 
        }
        
        try {
            AssetReport assetReportPayload = deserializeAssetReport(message.getPayload());
            CompletableFuture<SendResult<String, AssetReport>> future = kafkaTemplate.send(message.getTopic(), message.getMessageKey(), assetReportPayload);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    handleSuccessfulSend(message, result);
                } else {
                    handleFailedSend(message, ex);
                }
            });
        } catch (IOException deserializationEx) {
            logger.error("IOException while deserializing Avro payload (AssetReport) for message id: {}. Error: {}. Processing as FAILED.", message.getId(), deserializationEx.getMessage(), deserializationEx);
            // Deserialization failure is a terminal failure for this message, irrespective of attempt count.
            // The attempt count was already incremented and saved above.
            processTerminalFailure(message, "Avro deserialization failed.");
        }
    }

    private void handleSuccessfulSend(BufferedKafkaMessage message, SendResult<String, AssetReport> result) {
        try {
            messageRepository.delete(message);
            logger.info("Successfully sent and deleted Avro message (AssetReport) id: {}, key: {}, topic: {}, offset: {}",
                    message.getId(), message.getMessageKey(), message.getTopic(), result.getRecordMetadata().offset());
        } catch (Exception dbEx) {
            logger.error("Successfully sent Avro message (AssetReport) id: {} but FAILED TO DELETE from DB: {}. Message will remain as PENDING and likely be re-sent.",
                         message.getId(), dbEx.getMessage(), dbEx);
        }
    }

    private void handleFailedSend(BufferedKafkaMessage message, Throwable kafkaEx) {
        // This method is called when KafkaTemplate.send() completes exceptionally.
        // The attemptCount was already incremented and saved in initiateKafkaSendAndHandleOutcome.
        logger.warn("Failed to send Avro message (AssetReport) id: {} to Kafka (attempt {}/{}). Error: {}.",
                message.getId(), message.getAttemptCount(), maxSendAttempts, kafkaEx.getMessage());

        if (message.getAttemptCount() >= maxSendAttempts) {
            logger.error("Avro message (AssetReport) id: {} has reached max send attempts ({}) and will be processed as FAILED.",
                         message.getId(), maxSendAttempts);
            processTerminalFailure(message, "Max send attempts reached.");
        } else {
            // Message remains PENDING, its attempt count is already updated.
            logger.info("Message id {} remains PENDING for retry.", message.getId());
            // No need to re-save the message here as its state (attempt count) was saved before the send.
        }
    }

    private void processTerminalFailure(BufferedKafkaMessage message, String failureReason) {
        boolean dlqAttempted = false;
        boolean dlqSuccessful = false;

        if (dlqEnabled && dlqTopic != null && !dlqTopic.isBlank()) {
            try {
                logger.info("Attempting to send message id: {} (payload size: {} bytes) to DLQ topic: {}", 
                            message.getId(), message.getPayload().length, dlqTopic);
                // Using .get() for DLQ send for simplicity, makes it synchronous.
                dlqKafkaTemplate.send(dlqTopic, message.getMessageKey(), message.getPayload()).get(); 
                dlqSuccessful = true;
                dlqAttempted = true;
                logger.info("Successfully sent message id: {} to DLQ topic: {}", message.getId(), dlqTopic);
            } catch (Exception dlqEx) {
                dlqAttempted = true;
                logger.error("Failed to send message id: {} to DLQ topic '{}'. Error: {}", message.getId(), dlqTopic, dlqEx.getMessage(), dlqEx);
            }
        }

        try {
            messageRepository.delete(message);
            logger.info("Message id: {} (reason: {}) deleted from local buffer. DLQ attempted: {}, DLQ success: {}.", 
                        message.getId(), failureReason, dlqAttempted, dlqSuccessful);
        } catch (Exception dbEx) {
            logger.error("Message id: {} (reason: {}) FAILED TO DELETE from DB after terminal failure. DLQ attempted: {}, DLQ success: {}. Error: {}. Message may be reprocessed or re-sent to DLQ.",
                         message.getId(), failureReason, dlqAttempted, dlqSuccessful, dbEx.getMessage(), dbEx);
        }
    }

    private AssetReport deserializeAssetReport(byte[] avroBytes) throws IOException {
        SpecificDatumReader<AssetReport> reader = new SpecificDatumReader<>(AssetReport.class);
        ByteArrayInputStream in = new ByteArrayInputStream(avroBytes);
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(in, null);
        return reader.read(null, decoder);
    }
} 