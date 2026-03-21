package com.lavis.infra.persistence.repository;

import com.lavis.infra.persistence.entity.TaskRunLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TaskRunLogRepository extends JpaRepository<TaskRunLogEntity, Long> {

    List<TaskRunLogEntity> findByTaskIdOrderByStartTimeDesc(String taskId);

    List<TaskRunLogEntity> findByTaskIdAndStartTimeAfter(String taskId, LocalDateTime startTime);

    List<TaskRunLogEntity> findByStatus(String status);
}
