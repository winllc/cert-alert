package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.Project;
import java.time.Instant;

/**
 * A project as the list shows it.
 *
 * @param members how many people are in it
 * @param admins how many of them run it
 * @param servers how many servers
 */
public record ProjectRow(
        Long id,
        String name,
        String description,
        int members,
        int admins,
        int servers,
        String createdBy,
        Instant createdAt) {

    public static ProjectRow from(Project project, int members, int admins, int servers) {
        return new ProjectRow(
                project.getId(),
                project.getName(),
                project.getDescription(),
                members,
                admins,
                servers,
                project.getCreatedBy(),
                project.getCreatedAt());
    }

    /** Just enough to name it, for the badges on an entry's page. */
    public static ProjectRow of(Project project) {
        return from(project, 0, 0, 0);
    }
}
