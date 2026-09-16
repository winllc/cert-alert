package com.winllc.certalert.repository;

import com.winllc.certalert.domain.ChangelogCursor;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangelogCursorRepository extends JpaRepository<ChangelogCursor, Long> {

    Optional<ChangelogCursor> findByName(String name);
}
