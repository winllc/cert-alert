package com.winllc.certalert.service;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.ServerAttributeDefinition;
import com.winllc.certalert.domain.ServerAttributeType;
import com.winllc.certalert.ldap.LdapServerAttributes;
import com.winllc.certalert.ldap.LdapServerEntry;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.ServerAttributeDefinitionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The directory attributes this deployment lets people edit from here, and the editing
 * itself.
 *
 * <p>Two halves, deliberately apart. Which attributes are editable is the deployment's
 * shape and an administrator's: naming one adds a field to every server at once. Filling
 * one in is ordinary editing of one server - except that the server is the directory's, so
 * the value is read from the entry when a page asks and written back to it when somebody
 * changes it. Nothing is kept here in between: a copy of an attribute that can be edited in
 * two places is a copy that will disagree with itself.
 *
 * <p>What this application knows that the directory does not is the shape a value should
 * take: that {@code ATOStatus} is one of four words rather than free text, that a flag is a
 * flag. That is what a definition records, and what is checked before anything is written.
 * The directory's own schema still has the last word, and what it refuses comes back as it
 * was refused.
 */
@Service
public class ServerAttributeService {

    private static final Logger log = LoggerFactory.getLogger(ServerAttributeService.class);

    private static final int MAX_VALUE_LENGTH = 1000;

    /** What a directory holds for a boolean: RFC 4517 says these, in upper case. */
    private static final String TRUE = "TRUE";

    private final ServerAttributeDefinitionRepository definitions;
    private final DirectoryServerRepository servers;
    private final LdapServerAttributes directory;
    private final DirectoryPersistenceService persistence;
    private final AuditService auditService;
    private final Clock clock;

    public ServerAttributeService(
            ServerAttributeDefinitionRepository definitions,
            DirectoryServerRepository servers,
            LdapServerAttributes directory,
            DirectoryPersistenceService persistence,
            AuditService auditService,
            Clock clock) {
        this.definitions = definitions;
        this.servers = servers;
        this.directory = directory;
        this.persistence = persistence;
        this.auditService = auditService;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------------------
    // Which attributes are editable: an administrator's
    // -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ServerAttributeDefinition> list() {
        return definitions.findAllByOrderByDisplayOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public ServerAttributeDefinition get(Long id) {
        return definitions.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No managed attribute with id " + id));
    }

    @Transactional
    public ServerAttributeDefinition create(
            String ldapAttribute,
            String name,
            String description,
            ServerAttributeType type,
            boolean multiValued,
            List<String> options,
            Integer displayOrder,
            String actor) {

        String attribute = requireAttribute(ldapAttribute);
        String label = name == null || name.isBlank() ? attribute : name.trim();
        requireUnused(attribute, label, null);

        List<String> cleaned = cleanOptions(type, options);
        boolean several = multiValued && type.allowsMultipleValues();

        Instant now = Instant.now(clock);
        ServerAttributeDefinition definition = definitions.save(new ServerAttributeDefinition(
                attribute,
                label,
                blankToNull(description),
                type,
                several,
                cleaned,
                displayOrder == null ? nextOrder() : displayOrder,
                actor,
                now));
        log.info("The directory attribute {} is now editable here as '{}' ({}, {}), by {}",
                attribute, label, type, several ? "several values" : "one value", actor);
        return definition;
    }

    @Transactional
    public ServerAttributeDefinition update(
            Long id,
            String ldapAttribute,
            String name,
            String description,
            ServerAttributeType type,
            boolean multiValued,
            List<String> options,
            Integer displayOrder,
            String actor) {

        ServerAttributeDefinition definition = get(id);
        String attribute = requireAttribute(ldapAttribute);
        String label = name == null || name.isBlank() ? attribute : name.trim();
        requireUnused(attribute, label, id);

        definition.update(
                attribute,
                label,
                blankToNull(description),
                type,
                multiValued && type.allowsMultipleValues(),
                cleanOptions(type, options),
                displayOrder == null ? definition.getDisplayOrder() : displayOrder,
                actor,
                Instant.now(clock));
        log.info("Changed the managed attribute '{}' ({}), by {}", label, attribute, actor);
        return definition;
    }

    /**
     * Stops offering an attribute for editing. Nothing is deleted from the directory: the
     * values were never this application's to remove, and a server goes on holding whatever
     * it held.
     */
    @Transactional
    public void delete(Long id, String actor) {
        ServerAttributeDefinition definition = get(id);
        definitions.delete(definition);
        log.info("{} is no longer editable here; what servers hold for it is untouched, by {}",
                definition.getLdapAttribute(), actor);
    }

    // -------------------------------------------------------------------------------------
    // What one server holds: the directory's
    // -------------------------------------------------------------------------------------

    /**
     * What this server's entry holds for each managed attribute, read from the directory
     * rather than from here.
     *
     * @return values by definition id, in the order the directory returned them
     */
    @Transactional(readOnly = true)
    public Map<Long, List<String>> valuesFor(Long serverId) {
        DirectoryServer server = requireServer(serverId);
        List<ServerAttributeDefinition> managed = list();
        if (managed.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> held = directory.read(
                server.getDn(),
                LdapServerAttributes.namesOf(managed.stream()
                        .map(ServerAttributeDefinition::getLdapAttribute)
                        .toList()));

        Map<Long, List<String>> byDefinition = new LinkedHashMap<>();
        for (ServerAttributeDefinition definition : managed) {
            List<String> values = held.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(definition.getLdapAttribute()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(List.of());
            byDefinition.put(definition.getId(), fromDirectory(definition, values));
        }
        return byDefinition;
    }

    /**
     * Writes what this server should hold for one attribute, and brings the cached copy of
     * the entry back in step with what the directory now says.
     *
     * <p>The whole attribute at once, because that is what the page edits and because a
     * replace is one operation where a sequence of adds and removes is several - and a
     * half-applied change to who is responsible for a server is worse than a refused one.
     */
    @Transactional
    public List<String> setValues(Long serverId, Long definitionId, List<String> wanted, String actor) {
        DirectoryServer server = requireServer(serverId);
        ServerAttributeDefinition definition = get(definitionId);

        List<String> cleaned = clean(definition, wanted);
        List<String> before = valuesFor(serverId).getOrDefault(definitionId, List.of());
        if (cleaned.equals(before)) {
            return before;
        }

        directory.replace(server.getDn(), definition.getLdapAttribute(), toDirectory(definition, cleaned));
        refreshCachedEntry(server, actor);
        record(server, definition, before, cleaned, actor);
        return cleaned;
    }

    /** Whether this application binds as anybody, and so could write at all. */
    public boolean canWrite() {
        return directory.canWrite();
    }

    // -------------------------------------------------------------------------------------

    /**
     * A managed attribute may be one the sweep also caches - {@code ATOStatus} is a column
     * on the row as well as an attribute on the entry - so the entry is read back and put
     * through the same path a sweep uses. A failure here is logged and no more: the write
     * landed, and the next sweep will catch the copy up regardless.
     */
    private void refreshCachedEntry(DirectoryServer server, String actor) {
        try {
            LdapServerEntry entry = directory.reread(server.getDn());
            if (entry != null) {
                persistence.upsertServers(List.of(entry), Instant.now(clock), actor);
            }
        } catch (RuntimeException e) {
            log.warn("Wrote to {} but could not refresh the cached copy; the next sweep will",
                    server.getDn(), e);
        }
    }

    private void record(
            DirectoryServer server,
            ServerAttributeDefinition definition,
            List<String> before,
            List<String> after,
            String actor) {

        boolean cleared = after.isEmpty();
        String summary = cleared
                ? "Cleared %s (%s) in the directory, was %s"
                        .formatted(definition.getName(), definition.getLdapAttribute(), String.join(", ", before))
                : "Set %s (%s) in the directory to %s"
                        .formatted(definition.getName(), definition.getLdapAttribute(), String.join(", ", after));
        auditService.record(AuditEvent.about(
                        AuditEvent.SubjectRef.of(server),
                        cleared ? AuditAction.ATTRIBUTE_CLEARED : AuditAction.ATTRIBUTE_SET,
                        summary,
                        Instant.now(clock))
                .by(actor != null ? actor : AuditActors.current(AuditActors.SYSTEM))
                .to(definition.getLdapAttribute()));
    }

    /** What the page should show for what the directory holds. */
    private List<String> fromDirectory(ServerAttributeDefinition definition, List<String> values) {
        if (definition.getType() != ServerAttributeType.BOOLEAN) {
            return values;
        }
        // A directory writes TRUE and FALSE; the page has a switch, which is on or absent.
        return values.stream().anyMatch(value -> TRUE.equalsIgnoreCase(value)) ? List.of("true") : List.of();
    }

    /** And what the directory should hold for what the page says. */
    private List<String> toDirectory(ServerAttributeDefinition definition, List<String> values) {
        if (definition.getType() != ServerAttributeType.BOOLEAN) {
            return values;
        }
        return values.contains("true") ? List.of(TRUE) : List.of();
    }

    /** What will actually be written: in order, without repeats, and within the definition. */
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
        // "No" is the absence of the attribute: a directory has no such thing as an
        // attribute that is present and empty.
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

    private void requireUnused(String attribute, String label, Long self) {
        definitions.findByLdapAttributeIgnoreCase(attribute).ifPresent(existing -> {
            if (!existing.getId().equals(self)) {
                throw new AlreadyExistsException(
                        "Attribute already managed",
                        "%s is already managed here, as '%s'"
                                .formatted(existing.getLdapAttribute(), existing.getName()));
            }
        });
        definitions.findByNameIgnoreCase(label).ifPresent(existing -> {
            if (!existing.getId().equals(self)) {
                throw new AlreadyExistsException(
                        "Attribute already managed",
                        "There is already an attribute called " + existing.getName());
            }
        });
    }

    /**
     * An LDAP attribute type name, near enough: letters, digits and hyphens, starting with a
     * letter. What the directory will actually accept is the directory's to say.
     */
    private String requireAttribute(String ldapAttribute) {
        String trimmed = ldapAttribute == null ? "" : ldapAttribute.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Name the directory attribute this manages");
        }
        if (!trimmed.matches("^[a-zA-Z][a-zA-Z0-9-]*$")) {
            throw new IllegalArgumentException(
                    "'%s' is not an attribute name; letters, digits and hyphens, starting with a letter"
                            .formatted(trimmed));
        }
        return trimmed;
    }

    private DirectoryServer requireServer(Long serverId) {
        return servers.findById(serverId).orElseThrow(() -> ResourceNotFoundException.server(serverId));
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

    /** Lowercased attribute names, for matching what a directory returned. */
    static String key(String attribute) {
        return attribute == null ? "" : attribute.toLowerCase(Locale.ROOT);
    }
}
