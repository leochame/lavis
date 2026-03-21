package com.lavis.infra.persistence.repository;

import com.lavis.infra.persistence.entity.ScheduledTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTaskEntity, String> {

    List<ScheduledTaskEntity> findByEnabled(Boolean enabled);

    List<ScheduledTaskEntity> findByEnabledOrderByCreatedAtDesc(Boolean enabled);

    List<ScheduledTaskEntity> findBySourceTypeOrderByCreatedAtDesc(String sourceType);
}
