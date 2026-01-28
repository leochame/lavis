package com.lavis.skills;

import com.lavis.entity.AgentSkillEntity;
import com.lavis.repository.AgentSkillRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access layer for skills, wrapping AgentSkillRepository.
 */
@Component
public class SkillStore {

    private final AgentSkillRepository repository;

    public SkillStore(AgentSkillRepository repository) {
        this.repository = repository;
    }

    public AgentSkillEntity saveSkill(AgentSkillEntity skill) {
        if (skill.getId() == null) {
            skill.setId(UUID.randomUUID().toString());
        }
        return repository.save(skill);
    }

    public Optional<AgentSkillEntity> getSkill(String id) {
        return repository.findById(id);
    }

    public Optional<AgentSkillEntity> getSkillByName(String name) {
        return repository.findFirstByNameIgnoreCase(name);
    }

    public List<AgentSkillEntity> getAllSkills() {
        return repository.findAll();
    }

    public List<AgentSkillEntity> findEnabledSkills() {
        return repository.findByEnabledOrderByUseCountDesc(true);
    }

    public List<AgentSkillEntity> findSkillsByCategory(String category) {
        return repository.findByCategory(category);
    }

    public List<String> getAllCategories() {
        return repository.findAll().stream()
                .map(AgentSkillEntity::getCategory)
                .filter(c -> c != null && !c.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    public void deleteSkill(String id) {
        repository.deleteById(id);
    }

    public void incrementUseCount(String id) {
        repository.findById(id).ifPresent(skill -> {
            skill.setUseCount(skill.getUseCount() + 1);
            skill.setLastUsedAt(LocalDateTime.now());
            repository.save(skill);
        });
    }

    public boolean existsByName(String name) {
        return repository.existsByNameIgnoreCase(name);
    }
}
