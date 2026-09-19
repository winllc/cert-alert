package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.ServerAttributeDefinition;
import com.winllc.certalert.domain.ServerAttributeType;
import java.time.Instant;
import java.util.List;

/**
 * One managed attribute as the pages read it: which directory attribute it manages, what it
 * is called here, and what it may hold.
 *
 * @param ldapAttribute the attribute on the entry, which is where the values actually live
 * @param values what this server's entry holds for it, on a page about one server
 */
public record ServerAttributeRow(
        Long id,
        String ldapAttribute,
        String name,
        String description,
        ServerAttributeType type,
        String typeLabel,
        boolean multiValued,
        List<String> options,
        int displayOrder,
        List<String> values,
        String updatedBy,
        Instant updatedAt) {

    public static ServerAttributeRow of(ServerAttributeDefinition definition) {
        return from(definition, null);
    }

    public static ServerAttributeRow from(ServerAttributeDefinition definition, List<String> values) {
        return new ServerAttributeRow(
                definition.getId(),
                definition.getLdapAttribute(),
                definition.getName(),
                definition.getDescription(),
                definition.getType(),
                definition.getType().label(),
                definition.isMultiValued(),
                List.copyOf(definition.getOptions()),
                definition.getDisplayOrder(),
                values == null ? List.of() : List.copyOf(values),
                definition.getUpdatedBy(),
                definition.getUpdatedAt());
    }
}
