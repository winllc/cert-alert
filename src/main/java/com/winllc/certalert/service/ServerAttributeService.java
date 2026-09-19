package com.winllc.certalert.service;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.ServerAttributeDefinition;
import com.winllc.certalert.domain.ServerAttributeType;
import com.winllc.certalert.domain.ServerAttributeValue;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.ServerAttributeDefinitionRepository;
import com.winllc.certalert.repository.ServerAttributeValueRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The attributes this deployment keeps about its servers, and what each server holds for
 * them.
 *
 * <p>Two halves, deliberately apart. The definitions are the deployment's shape and are an
 * administrator's: adding one adds a field to every server at once, and retiring one takes
 * every value with it. Filling them in is ordinary editing of one server.
 *
 * <p>What a definition may become once servers hold values for it is bounded by those
 * values rather than by nothing: an option still in use cannot be taken out of the list,
 * "several" cannot become "one" while a server holds two, and the kind of thing an
 * attribute is cannot change underneath values recorded as something else. Every one of
 * those refusals names what is in the way, because "cannot" without "because" means
 * somebody goes looking in the database.
 */
@Service
public class ServerAttributeService {

    private static final Logger log = LoggerFactory.getLogger(ServerAttributeService.class);

    private static final int MAX_VALUE_LENGTH = 1000;

    private final ServerAttributeDefinitionRepository definitions;
    private final ServerAttributeValueRepository values;
    private final DirectoryServerRepository servers;
    private final AuditService auditService;
    private final Clock clock;

    public ServerAttributeService(
            ServerAttributeDefinitionRepository definitions,
            ServerAttributeValueRepository values,
            DirectoryServerRepository servers,
            AuditService auditService,
            Clock clock) {
        this.definitions = definitions;
        this.values = values;
        this.servers = servers;
        this.auditService = auditService;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------------------
    // What the deployment keeps: an administrator's
    // -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ServerAttributeDefinition> list() {
        return definitions.findAllByOrderByDisplayOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public ServerAttributeDefinition get(Long id) {
        return definitions.findById(id).orElseThrow(() -> new ResourceNotFoundException(
                "No managed attribute with id " + id));
    }

    @Transactional
    public ServerAttributeDefinition create(
            String name,
            String description,
            ServerAttributeType type,
            boolean multiValued,
            List<String> options,
            Integer displayOrder,
            String actor) {

        String trimmed = requireName(name);
        definitions.findByNameIgnoreCase(trimmed).ifPresent(existing -> {
            throw new ContactAlreadyExistsException("There is already an attribute called " + existing.getName());
        });

        List<String> cleaned = cleanOptions(type, options);
        boolean several = multiValued && type.allowsMultipleValues();
        Instant now = Instant.now(clock);

        ServerAttributeDefinition definition = definitions.save(new ServerAttributeDefinition(
                trimmed,
                blankToNull(description),
                type,
                several,
                cleaned,
                displayOrder == null ? nextOrder() : displayOrder,
                actor,
                now));
        log.info("Added the managed server attribute '{}' ({}, {}), by {}",
                trimmed, type, several ? "several values" : "one value", actor);
        return definition;
    }

    @Transactional
    public ServerAttributeDefinition update(
            Long id,
            String name,
            String description,
            ServerAttributeType type,
            boolean multiValued,
            List<String> options,
            Integer displayOrder,
            String actor) {

        ServerAttributeDefinition definition = get(id);
        String trimmed = requireName(name);
        definitions.findByNameIgnoreCase(trimmed).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new ContactAlreadyExistsException("There is already an attribute called " + existing.getName());
            }
        });

        List<String> cleaned = cleanOptions(type, options);
        boolean several = multiValued && type.allowsMultipleValues();

        if (type != definition.getType() && values.countByDefinitionId(id) > 0) {
            throw new IllegalArgumentException(
                    "This attribute cannot change kind while servers hold values for it; clear them first");
        }
        if (!several && definition.isMultiValued() && values.widestServer(id) > 1) {
            throw new IllegalArgumentException(
                    "A server holds more than one value for this attribute; it cannot be narrowed to one");
        }
        if (type == ServerAttributeType.CHOICE) {
            List<String> stillUsed = values.countByValue(id).stream()
                    .map(ServerAttributeValueRepository.ValueUsage::getValue)
                    .filter(value -> !cleaned.contains(value))
                    .sorted()
                    .toList();
            if (!stillUsed.isEmpty()) {
                throw new IllegalArgumentException(
                        "Servers still hold %s, so %s cannot be removed from the list"
                                .formatted(String.join(", ", stillUsed), stillUsed.size() == 1 ? "it" : "they"));
            }
        }

        definition.update(
                trimmed,
                blankToNull(description),
                type,
                several,
                cleaned,
                displayOrder == null ? definition.getDisplayOrder() : displayOrder,
                actor,
                Instant.now(clock));
        log.info("Changed the managed server attribute '{}', by {}", trimmed, actor);
        return definition;
    }

    /** Retires an attribute. The values go with it: they mean nothing without it. */
    @Transactional
    public void delete(Long id, String actor) {
        ServerAttributeDefinition definition = get(id);
        long held = values.countByDefinitionId(id);
        definitions.delete(definition);
        log.info("Removed the managed server attribute '{}' and {} value(s) held for it, by {}",
                definition.getName(), held, actor);
    }

    // -------------------------------------------------------------------------------------
    // What one server holds
    // -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<Long, List<String>> valuesFor(Long serverId) {
        return values.findByServerIdOrderByPositionAscIdAsc(serverId).stream()
                .collect(Collectors.groupingBy(
                        value -> value.getDefinition().getId(),
                        Collectors.mapping(ServerAttributeValue::getValue, Collectors.toList())));
    }

    /**
     * Sets everything this server holds for one attribute, replacing whatever was there.
     *
     * <p>Replacing rather than adding and removing one at a time: the page edits an
     * attribute as a whole - a box, a drop-down, a switch - so that is what it sends, and
     * the audit record is one line about what the attribute now says rather than a scatter.
     */
    @Transactional
    public List<String> setValues(Long serverId, Long definitionId, List<String> wanted, String actor) {
        DirectoryServer server = servers.findById(serverId)
                .orElseThrow(() -> ResourceNotFoundException.server(serverId));
        ServerAttributeDefinition definition = get(definitionId);

        List<String> cleaned = clean(definition, wanted);
        List<String> before = values.findByServerIdAndDefinitionIdOrderByPositionAscIdAsc(serverId, definitionId)
                .stream()
                .map(ServerAttributeValue::getValue)
                .toList();
        if (cleaned.equals(before)) {
            return before;
        }

        Instant now = Instant.now(clock);
        values.deleteByServerIdAndDefinitionId(serverId, definitionId);
        // Flushed before the inserts, or the unique key sees the old rows and the new ones
        // at once when a value is only being reordered.
        values.flush();

        List<ServerAttributeValue> saved = new ArrayList<>();
        for (int position = 0; position < cleaned.size(); position++) {
            saved.add(new ServerAttributeValue(
                    definition, server, cleaned.get(position), position, actor, now));
        }
        values.saveAll(saved);

        record(server, definition, before, cleaned, actor, now);
        log.info("Set '{}' on {} to {}, by {}", definition.getName(), server.getDn(), cleaned, actor);
        return cleaned;
    }

    // -------------------------------------------------------------------------------------

    private void record(
            DirectoryServer server,
            ServerAttributeDefinition definition,
            List<String> before,
            List<String> after,
            String actor,
            Instant now) {

        boolean cleared = after.isEmpty();
        String summary = cleared
                ? "Cleared %s (was %s)".formatted(definition.getName(), String.join(", ", before))
                : "Set %s to %s".formatted(definition.getName(), String.join(", ", after));
        auditService.record(AuditEvent.about(
                        AuditEvent.SubjectRef.of(server),
                        cleared ? AuditAction.ATTRIBUTE_CLEARED : AuditAction.ATTRIBUTE_SET,
                        summary,
                        now)
                .by(actor != null ? actor : AuditActors.current(AuditActors.SYSTEM))
                .to(definition.getName()));
    }

    /** What the deployment will actually store for this attribute, in order and without repeats. */
    private List<String> clean(ServerAttributeDefinition definition, List<String> wanted) {
        List<String> cleaned = new ArrayList<>(new LinkedHashSet<>(wanted == null
                ? List.of()
                : wanted.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .toList()));

        if (!definition.isMultiValued() && cleaned.size() > 1) {
            throw new IllegalArgumentException(
                    "%s holds one value, not %d".formatted(definition.getName(), cleaned.size()));
        }
        for (String value : cleaned) {
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "A value for %s is longer than %d characters".formatted(definition.getName(), MAX_VALUE_LENGTH));
            }
            if (!definition.permits(value)) {
                throw new IllegalArgumentException(switch (definition.getType()) {
                    case CHOICE -> "'%s' is not one of the values %s may hold".formatted(value, definition.getName());
                    case BOOLEAN -> "%s is yes or no, not '%s'".formatted(definition.getName(), value);
                    case TEXT -> "'%s' is not a value %s may hold".formatted(value, definition.getName());
                });
            }
        }
        // "No" is the absence of the attribute rather than a value to store, so a boolean
        // that is off holds nothing at all and reads as unset everywhere.
        if (definition.getType() == ServerAttributeType.BOOLEAN && cleaned.equals(List.of("false"))) {
            return List.of();
        }
        return cleaned;
    }

    private List<String> cleanOptions(ServerAttributeType type, List<String> options) {
        if (type != ServerAttributeType.CHOICE) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(new LinkedHashSet<>(options == null
                ? List.of()
                : options.stream()
                        .filter(option -> option != null && !option.isBlank())
                        .map(String::trim)
                        .toList()));
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("A drop-down needs at least one value to choose from");
        }
        return cleaned;
    }

    private String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("An attribute needs a name");
        }
        return trimmed;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int nextOrder() {
        return definitions.findAll().stream()
                .map(ServerAttributeDefinition::getDisplayOrder)
                .max(Integer::compareTo)
                .map(highest -> highest + 1)
                .orElse(0);
    }

    /** Definitions by id, for a page that renders values against them. */
    public static Map<Long, ServerAttributeDefinition> byId(List<ServerAttributeDefinition> definitions) {
        return definitions.stream()
                .collect(Collectors.toMap(ServerAttributeDefinition::getId, Function.identity()));
    }
}
