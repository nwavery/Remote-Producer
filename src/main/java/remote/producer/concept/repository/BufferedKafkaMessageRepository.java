package remote.producer.concept.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import remote.producer.concept.model.BufferedKafkaMessage;
import remote.producer.concept.model.MessageStatus;

import java.util.List;

@Repository
public interface BufferedKafkaMessageRepository extends JpaRepository<BufferedKafkaMessage, Long> {

    List<BufferedKafkaMessage> findByStatus(MessageStatus status, Pageable pageable);

    // This query is useful if you want to mark messages as SENDING to prevent other instances
    // (if you scale this producer) from picking up the same messages.
    // For a single instance, simply fetching PENDING messages is often enough.
    @Modifying
    @Query("UPDATE BufferedKafkaMessage m SET m.status = :newStatus WHERE m.id IN :ids AND m.status = :oldStatus")
    int updateStatusForIds(@Param("ids") List<Long> ids, @Param("oldStatus") MessageStatus oldStatus, @Param("newStatus") MessageStatus newStatus);

} 