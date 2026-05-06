package dev.agiro.masterserver.repository;

import dev.agiro.masterserver.model.AdventureSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdventureSessionRepository extends JpaRepository<AdventureSession, String> {

    List<AdventureSession> findByWorldId(String worldId);

    List<AdventureSession> findByWorldIdAndAdventureModuleIdOrderByUpdatedAtDesc(String worldId, String adventureModuleId);
}
