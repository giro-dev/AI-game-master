package dev.agiro.masterserver.repository;

import dev.agiro.masterserver.model.AdventureModule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdventureModuleRepository extends JpaRepository<AdventureModule, String> {

    List<AdventureModule> findByWorldId(String worldId);

    List<AdventureModule> findBySystem(String system);
}
