package com.university.docassistant.repository;

import com.university.docassistant.dto.NotificationDtos.NotificationResponse;
import com.university.docassistant.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Notifications, always of one user: every query here takes that user's id, so nobody reaches another's. List
 * methods return {@link NotificationResponse} rows built in the query.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query(value = NotificationResponse.SELECT + "WHERE n.user.id = :userId ORDER BY n.createdAt DESC, n.id DESC",
            countQuery = "SELECT COUNT(n) FROM Notification n WHERE n.user.id = :userId")
    Page<NotificationResponse> findViewsByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query(NotificationResponse.SELECT + "WHERE n.id = :id AND n.user.id = :userId")
    Optional<NotificationResponse> findViewByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.user.id = :userId AND n.readAt IS NULL")
    long countUnread(@Param("userId") Long userId);

    /** Marks one of the user's own notifications read (once); 0 when it is not theirs or already read. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.id = :id AND n.user.id = :userId AND n.readAt IS NULL")
    int markRead(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.user.id = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
