package com.lavis.repository;

import com.lavis.entity.UserPreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserPreferenceRepository extends JpaRepository<UserPreferenceEntity, Long> {

    Optional<UserPreferenceEntity> findByPreferenceKey(String preferenceKey);

    void deleteByPreferenceKey(String preferenceKey);
}
