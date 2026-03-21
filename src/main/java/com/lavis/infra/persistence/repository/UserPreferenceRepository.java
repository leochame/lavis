package com.lavis.infra.persistence.repository;

import com.lavis.infra.persistence.entity.UserPreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserPreferenceRepository extends JpaRepository<UserPreferenceEntity, Long> {

    Optional<UserPreferenceEntity> findByPreferenceKey(String preferenceKey);

    void deleteByPreferenceKey(String preferenceKey);
}
