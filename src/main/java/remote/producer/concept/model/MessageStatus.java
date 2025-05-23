package remote.producer.concept.model;

public enum MessageStatus {
    PENDING,  // Message is waiting to be sent
    SENDING   // Message is currently being processed for sending (intermediate state, not directly set by KafkaMessageSenderService anymore but conceptually exists)
} 