package dev.agiro.masterserver.repository;

import dev.agiro.masterserver.entity.SystemProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SystemProfileJpaRepository extends JpaRepository<SystemProfileEntity, String> {
}
