package com.winllc.certalert.inventory;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedViewRepository extends JpaRepository<SavedView, Long> {

    List<SavedView> findByOwnerAndPageOrderByNameAsc(String owner, String page);

    Optional<SavedView> findByOwnerAndPageAndName(String owner, String page, String name);

    long countByOwner(String owner);
}
