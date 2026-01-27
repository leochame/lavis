package com.lavis.repository;

import com.lavis.entity.UserSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSessionEntity, String> {

    Optional<UserSessionEntity> findBySessionKey(String sessionKey);

    List<UserSessionEntity> findByLastActiveAtAfter(LocalDateTime lastActiveAt);

    List<UserSessionEntity> findAllByOrderByLastActiveAtDesc();
}
