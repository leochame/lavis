package com.lavis.repository;

import com.lavis.entity.AgentSkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentSkillRepository extends JpaRepository<AgentSkillEntity, String> {

    List<AgentSkillEntity> findByEnabled(Boolean enabled);

    List<AgentSkillEntity> findByCategory(String category);

    List<AgentSkillEntity> findByEnabledOrderByUseCountDesc(Boolean enabled);

    List<AgentSkillEntity> findByEnabledAndCategoryOrderByUseCountDesc(Boolean enabled, String category);
}
