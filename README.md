# Resilient Kafka Producer for Remote Environments

This project implements a Kafka producer in Java 21 with Spring Boot 3+, designed for environments with unreliable or spotty network connections to a Kafka cluster (e.g., Confluent Cloud). It ensures that messages are not lost during network outages and are sent when connectivity is restored.

## Core Problem Solved

In remote or edge deployments, network connectivity to a central Kafka cluster can be intermittent. A standard Kafka producer might lose messages or fail if it cannot connect for an extended period. This solution provides a local persistent buffer to hold messages and a mechanism to retry sending them when the network is available.

## Key Features

*   **Local Message Buffering**: Messages are first saved to a local H2 database.
*   **Asynchronous Sending**: A scheduled service periodically attempts to send buffered messages.
*   **Resilience to Network Issues**: Producer settings are tuned for retries and timeouts.
*   **Avro Serialization**: Messages are serialized using Apache Avro, with schemas managed by Confluent Schema Registry, ensuring data integrity and schema evolution. The example schema used is `exampleSchema.json` generating `com.example.platform.telemetry.AssetReport`.
*   **Configurable Retries**: Maximum send attempts for a message can be configured before it's marked as FAILED.
*   **Idempotent Producer**: Configured for exactly-once semantics (EOS) on the producer side.
*   **Efficient Batching & Compression**: Producer settings for batch size and compression are configurable.
*   **Virtual Threads**: Leverages Java 21 Virtual Threads via Spring Boot for non-blocking send operations where applicable.
*   **Dead Letter Queue (DLQ)**: Terminally failed messages (due to max retries or deserialization errors) can be sent to a configurable DLQ topic before being deleted from the local buffer.
*   **Connectivity-Aware Sending with Exponential Backoff**: The producer can check for network connectivity before attempting to send messages. If a connection cannot be established, it employs an exponential backoff strategy to pause sending attempts, resuming when connectivity is likely restored. This prevents exhausting send retries during prolonged offline periods.

## Architecture and Workflow

The producer employs a two-stage approach: local buffering and asynchronous sending.

1.  **Message Buffering (using Avro for payload):
    *   When your application needs to send a Kafka message (e.g., a `com.example.platform.telemetry.AssetReport` object), it calls the `bufferMessage(String topic, String key, AssetReport payload)` method (or its overload for the default topic) in `LocalBufferingKafkaProducerService`.
    *   The `AssetReport` payload is serialized into `byte[]` using Apache Avro.
    *   This service saves the topic, key, and serialized Avro `byte[]` payload into a `BufferedKafkaMessage` entity in the local H2 database. The message status is `PENDING`.

2.  **Asynchronous Message Sending (Scheduled Task - `processPendingMessages` in `KafkaMessageSenderService`):
    *   The service runs at a configurable interval (`app.messaging.buffer.send-interval-ms`).
    *   **Connectivity Check (if `app.connectivity.check.enabled=true`):**
        *   Before processing messages, it attempts a quick network connection (socket connect) to a target host/port. 
        *   The target can be explicitly configured (`app.connectivity.check.target.host/port`) or defaults to the first Kafka bootstrap server.
        *   **If Connected**: Assumes connectivity is OK. Resets any backoff period.
        *   **If Not Connected**: Assumes connectivity is lost. 
            *   Logs the failure.
            *   Enters/continues an exponential backoff period (starts with `app.connectivity.initial.backoff.ms`, grows by `app.connectivity.backoff.multiplier`, capped by `app.connectivity.max.backoff.ms`).
            *   Skips the rest of the message processing cycle for this scheduled run.
    *   **Message Processing (if connectivity is OK or check is disabled):**
        *   Queries the H2 database for a batch of `PENDING` messages.
        *   For each message:
            *   The `attemptCount` is incremented, and `lastAttemptAt` is updated *just before* the actual send to Kafka is initiated.
            *   The Avro `byte[]` payload is deserialized into an `AssetReport` object.
            *   The `KafkaTemplate` attempts to send the `AssetReport` object to Kafka.
            *   **Success**: Message deleted from H2.
            *   **Failure (Kafka Client Error)**: 
                *   If `attemptCount` < `app.messaging.buffer.max-send-attempts`, the message remains `PENDING` in H2 for the next cycle (when connectivity is available).
                *   If `attemptCount` >= `app.messaging.buffer.max-send-attempts`, it's a terminal failure (see below).
            *   **Deserialization Failure**: This is an immediate terminal failure.
    *   **Terminal Failure Handling**: 
        *   If DLQ is enabled, the raw `byte[]` payload is sent to the DLQ topic.
        *   The message is deleted from H2.

## Configuration

Key configuration properties are in `src/main/resources/application.properties`:

*   **H2 Database**: `spring.datasource.url`, etc.
*   **Confluent Cloud Kafka Connection**: `spring.kafka.bootstrap-servers`, `spring.kafka.properties.security.protocol`, `spring.kafka.properties.sasl.mechanism`, `spring.kafka.properties.sasl.jaas.config`.
*   **Confluent Schema Registry**: `spring.kafka.properties.schema.registry.url` and associated authentication properties (e.g., `spring.kafka.properties.basic.auth.credentials.source`, `spring.kafka.properties.basic.auth.user.info`). **YOU MUST CONFIGURE THESE.**
*   **Kafka Producer Settings**: `spring.kafka.producer.value-serializer` (set to `io.confluent.kafka.serializers.KafkaAvroSerializer`), `acks`, `retries`, `enable.idempotence`, `max.in.flight.requests.per.connection`, `delivery.timeout.ms`, `batch-size`, `compression-type`.
*   **Application-Specific Buffering Logic**: `kafka.producer.topic` (default topic), `app.messaging.buffer.send-interval-ms`, `app.messaging.buffer.batch-size`, `app.messaging.buffer.max-send-attempts`.
*   **Dead Letter Queue (DLQ)**: 
    *   `app.messaging.dlq.enabled`: Set to `true` to enable sending terminally failed messages to a DLQ. Defaults to `true`.
    *   `app.messaging.dlq.topic`: The Kafka topic name for the DLQ. **Required if DLQ is enabled.** Example: `your-asset-reports-dlq`.
*   **Connectivity Check & Backoff**:
    *   `app.connectivity.check.enabled=true`: Enables the pre-send connectivity check. Defaults to `true`.
    *   `app.connectivity.check.target.host=`: Optional. Specific host to test connectivity (e.g., a known stable endpoint or one of your Kafka brokers). If blank, defaults to the host from the first `spring.kafka.bootstrap-servers` entry.
    *   `app.connectivity.check.target.port=`: Optional. Port for the `target.host`. If blank and `target.host` is from Kafka config, defaults to the port from Kafka config.
    *   `app.connectivity.check.timeout.ms=5000`: Timeout in milliseconds for the socket connection attempt during the check. Defaults to 5000ms.
    *   `app.connectivity.initial.backoff.ms=60000`: Initial duration (ms) to wait after a failed connectivity check before retrying the check. Defaults to 1 minute.
    *   `app.connectivity.max.backoff.ms=1800000`: Maximum duration (ms) for the backoff. Defaults to 30 minutes.
    *   `app.connectivity.backoff.multiplier=2`: Multiplier for increasing the backoff duration (e.g., 1m -> 2m -> 4m -> ... up to max). Defaults to 2.
*   **Virtual Threads**: `spring.threads.virtual.enabled=true` (for Java 21+).

## Avro Schema Management

*   Place your Avro schema files (e.g., `exampleSchema.json`) in `src/main/avro/`.
*   The `avro-maven-plugin` in `pom.xml` will automatically generate the corresponding Java POJOs (e.g., `com.example.platform.telemetry.AssetReport`) during the Maven build process (e.g., `mvn compile`).
*   Ensure your Confluent Cloud Schema Registry is accessible and configured correctly in `application.properties`.

## How it Achieves Resilience

1.  **Local Persistence**: Messages are safe on local disk (H2 database).
2.  **Connectivity-Aware Sending & Exponential Backoff**: If the network (or Kafka endpoint) is unreachable, the producer pauses send attempts using an exponential backoff strategy. This prevents rapidly exhausting send retries during extended outages. Send retries are only consumed when an active attempt to send to Kafka is made.
3.  **Application-Level Retries**: When connectivity is available, the `KafkaMessageSenderService` retries sending messages that previously failed (up to `max-send-attempts`).
4.  **Kafka Producer Internal Retries**: The `KafkaTemplate` itself handles short-lived transient network issues during an active send operation.
5.  **Idempotence**: Prevents duplicate messages in Kafka.
6.  **Acknowledgment (`acks=all`)**: Ensures data durability in Kafka.
7.  **Dead Letter Queue**: Terminally problematic messages are routed to a DLQ for investigation and then removed from the local buffer.
8.  **Schema Evolution**: Using Avro and Schema Registry.

## To Run

1.  **Prerequisites**:
    *   Java 21 JDK
    *   Apache Maven
    *   Access to a Confluent Cloud Kafka cluster and Schema Registry.
2.  **Configuration**:
    *   Update `src/main/resources/application.properties` with your Confluent Cloud Kafka broker details, API keys/secrets, and Schema Registry URL and credentials.
    *   Ensure your `exampleSchema.json` (or other Avro schemas) are in `src/main/avro/`.
3.  **Build**:
    ```bash
    mvn clean package
    ```
    This will compile the code, generate Avro Java classes, and create an executable JAR.
4.  **Run**:
    ```bash
    java -jar target/<your-artifact-name>.jar
    ```

## Potential Future Enhancements

*   **More Sophisticated Connectivity Probing**: The current socket test is basic. More advanced strategies could involve multiple target hosts or an application-level PING/PONG with the Kafka cluster if supported.
*   **Dynamic Configuration**: Allow some properties (especially backoff settings) to be updated at runtime.
*   **Monitoring/Metrics**: Expose metrics about connectivity status, backoff periods, buffer size, etc.
*   **User Interface**: A simple web UI.

## Resilience Mechanisms

This design incorporates several layers of resilience:

1.  **Local Persistent Buffering**: The primary mechanism. Messages are safe on local disk (H2 database) even if the application restarts or the network is down for hours or days. This decouples message production from the immediate need for network connectivity.
2.  **Connectivity Check with Exponential Backoff**: Before attempting to send messages, the system can perform a network connectivity test. If this fails, it backs off exponentially (e.g., 1 min, 2 min, 4 min, up to a configurable maximum like 30 mins or 1 hour) before trying the connectivity check again. This ensures that during prolonged outages (hours, days), the system isn't constantly trying to send and exhausting application-level retries. Send attempts are only made when connectivity is believed to be present.
3.  **Application-Level Retries When Connected**: Once connectivity is established (or if the check is disabled), the `KafkaMessageSenderService` attempts to send messages from the buffer. If a send fails (after Kafka client's internal retries and timeouts), the message remains in the buffer, and its application-level attempt count is incremented. This will be retried up to `app.messaging.buffer.max-send-attempts` times in subsequent processing cycles as long as connectivity holds.
4.  **Kafka Producer Internal Retries**: The `KafkaTemplate` itself is configured with a high number of retries (`spring.kafka.producer.retries=Integer.MAX_VALUE`) and a delivery timeout (`spring.kafka.producer.properties.delivery.timeout.ms=120000`). These handle short-lived transient network issues that might occur *while an active connection to Kafka is established and an attempt to send a batch is in progress*.
5.  **Idempotent Producer**: Ensures exactly-once semantics at the producer level.
6.  **Acknowledgment (`acks=all`)**: Ensures messages are replicated in Kafka before success is reported.
7.  **Dead Letter Queue Processing**: After exhausting application-level retries *while connected*, or if a message has an unrecoverable issue like a deserialization error, it is sent to a DLQ (if enabled) and then removed from the local H2 buffer.

## Key Configuration (`src/main/resources/application.properties`)

*   **H2 Database**:
    *   `spring.datasource.url=jdbc:h2:file:./local_message_buffer;DB_CLOSE_DELAY=-1`: Path to the local database file.
*   **Confluent Cloud Connection**:
    *   `kafka.producer.topic`: Default Kafka topic (placeholder: `your-topic-name`).
    *   `spring.kafka.bootstrap-servers`: Your Confluent Cloud bootstrap server addresses (placeholder).
    *   `spring.kafka.properties.sasl.jaas.config`: Your Confluent Cloud API key and secret for SASL/PLAIN authentication (placeholders).
*   **Producer Reliability & Performance**:
    *   `spring.kafka.producer.acks=all`
    *   `spring.kafka.producer.retries=2147483647`
    *   `spring.kafka.producer.properties.enable.idempotence=true`
    *   `spring.kafka.producer.properties.max.in.flight.requests.per.connection=5`
    *   `spring.kafka.producer.properties.delivery.timeout.ms=120000` (2 minutes for Kafka client send attempts)
    *   `spring.kafka.producer.batch-size=32768` (32KB)
    *   `spring.kafka.producer.compression-type=lz4`
*   **Application Buffering Logic**:
    *   `app.messaging.buffer.send-interval-ms=60000` (Check H2 buffer every 60 seconds).
    *   `app.messaging.buffer.batch-size=50` (Attempt to send up to 50 messages from buffer per cycle when connected).
    *   `app.messaging.buffer.max-send-attempts=10` (Max send attempts *when connected* before routing to DLQ).
*   **Dead Letter Queue Configuration**:
    *   `app.messaging.dlq.enabled=true`: Enables publishing to DLQ.
    *   `app.messaging.dlq.topic=your-dlq-topic-name`: **You must set this to your desired DLQ topic name.**
*   **Connectivity Check & Backoff Configuration**:
    *   `app.connectivity.check.enabled=true`
    *   `app.connectivity.check.target.host=` (e.g., your Kafka broker host, or leave blank to use first bootstrap server)
    *   `app.connectivity.check.target.port=` (e.g., your Kafka broker port, or leave blank)
    *   `app.connectivity.check.timeout.ms=5000`
    *   `app.connectivity.initial.backoff.ms=60000` (1 minute)
    *   `app.connectivity.max.backoff.ms=1800000` (30 minutes)
    *   `app.connectivity.backoff.multiplier=2`
*   **Java 21 Virtual Threads**:
    *   `spring.threads.virtual.enabled=true`: Enables Spring Boot to use virtual threads where appropriate.

## How to Use

1.  **Configure Confluent Cloud**:
    *   Open `src/main/resources/application.properties`.
    *   Replace placeholder values for `kafka.producer.topic`, `spring.kafka.bootstrap-servers`, and `spring.kafka.properties.sasl.jaas.config` with your actual Confluent Cloud details.
2.  **Run the Application**:
    *   Build and run the Spring Boot application.
3.  **Send Messages**:
    *   Inject the `LocalBufferingKafkaProducerService`.
    *   Call `localBufferingKafkaProducerService.bufferMessage("your-key", assetReportObject);` where `assetReportObject` is an instance of `com.example.platform.telemetry.AssetReport`.