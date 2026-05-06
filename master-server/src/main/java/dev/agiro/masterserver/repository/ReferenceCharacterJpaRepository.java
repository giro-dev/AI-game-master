package dev.agiro.masterserver.repository;

import dev.agiro.masterserver.entity.ReferenceCharacterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReferenceCharacterJpaRepository extends JpaRepository<ReferenceCharacterEntity, String> {
}
