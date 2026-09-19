package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.ServerAttributeDefinition;
import com.winllc.certalert.domain.ServerAttributeType;
import java.time.Instant;
import java.util.List;

/**
 * One managed attribute as the pages read it: what it is called, what it may hold, and -
 * where the list of them is being administered - how many servers hold it, because that is
 * what makes retiring one a decision rather than a click.
 *
 * @param values what this server holds for it, on a page about one server
 */
public record ServerAttributeRow(
        Long id,
        String name,
        String description,
        ServerAttributeType type,
        String typeLabel,
        boolean multiValued,
        List<String> options,
        int displayOrder,
        List<String> values,
        Long serversHolding,
        String updatedBy,
        Instant updatedAt) {

    public static ServerAttributeRow of(ServerAttributeDefinition definition) {
        return from(definition, null, null);
    }

    public static ServerAttributeRow from(
            ServerAttributeDefinition definition, List<String> values, Long serversHolding) {

        return new ServerAttributeRow(
                definition.getId(),
                definition.getName(),
                definition.getDescription(),
                definition.getType(),
                definition.getType().label(),
                definition.isMultiValued(),
                List.copyOf(definition.getOptions()),
                definition.getDisplayOrder(),
                values == null ? List.of() : List.copyOf(values),
                serversHolding,
                definition.getUpdatedBy(),
                definition.getUpdatedAt());
    }
}
