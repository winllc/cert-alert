package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.ServerAttributeDefinition;
import com.winllc.certalert.domain.ServerAttributeType;
import com.winllc.certalert.repository.AuditEventRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.ServerAttributeDefinitionRepository;
import com.winllc.certalert.support.EmbeddedDirectory;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Editing the directory's own attributes from here.
 *
 * <p>Driven against a real directory, and asserted against the entry rather than against
 * anything this application stored: the whole point is that the value ends up in the
 * directory, so reading it back out of our own tables would prove nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
class ServerAttributeTest {

    private static EmbeddedDirectory directory;

    @Autowired
    private ServerAttributeService attributes;

    @Autowired
    private ServerAttributeDefinitionRepository definitions;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryServerRepository servers;

    @Autowired
    private AuditEventRepository auditRepository;

    /** The directory outlives each test, so the entry is added once and reset per test. */
    private static String webDn;

    private Long webId;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
        webDn = directory.addServer(
                "attr-web01", "https://attr-web01.example.gov", new String[] {"ops@example.gov"});
    }

    @AfterAll
    static void stopDirectory() {
        if (directory != null) {
            directory.close();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
    }

    @BeforeEach
    void seed() {
        definitions.deleteAll();
        auditRepository.deleteAll();
        servers.deleteAll();

        // Left as the directory would have it between tests.
        directory.modify(webDn, "ATOStatus");
        directory.modify(webDn, "lifeCycleStatus");
        directory.modify(webDn, "icNetworks");
        syncService.syncServers();
        webId = servers.findByDn(webDn).orElseThrow().getId();
    }

    @Test
    void whatIsTypedHereEndsUpInTheDirectory() {
        ServerAttributeDefinition ato = attributes.create(
                "ATOStatus", "ATO status", "Authorization to operate", ServerAttributeType.CHOICE, false,
                List.of("Authorized", "Denied", "Expired"), null, "alice");

        attributes.setValues(webId, ato.getId(), List.of("Authorized"), "alice");

        assertThat(directory.valuesOf(webDn, "ATOStatus")).containsExactly("Authorized");
        assertThat(attributes.valuesFor(webId)).containsEntry(ato.getId(), List.of("Authorized"));
    }

    /** And what is in the directory is what the page shows, however it got there. */
    @Test
    void whatTheDirectorySaysIsWhatIsRead() {
        ServerAttributeDefinition ato = attributes.create(
                "ATOStatus", "ATO status", null, ServerAttributeType.TEXT, false, null, null, "alice");

        directory.modify(webDn, "ATOStatus", "Authorized");

        assertThat(attributes.valuesFor(webId)).containsEntry(ato.getId(), List.of("Authorized"));
    }

    @Test
    void severalValuesAreWrittenAsSeveralAttributeValues() {
        ServerAttributeDefinition networks = attributes.create(
                "icNetworks", "Networks", null, ServerAttributeType.TEXT, true, null, null, "alice");

        attributes.setValues(webId, networks.getId(), List.of("JWICS", "SIPRNET", "JWICS"), "alice");

        assertThat(directory.valuesOf(webDn, "icNetworks")).containsExactlyInAnyOrder("JWICS", "SIPRNET");
        assertThat(attributes.valuesFor(webId).get(networks.getId())).hasSize(2);
    }

    /** Clearing takes the attribute off the entry: a directory has no empty attribute. */
    @Test
    void clearingTakesTheAttributeOffTheEntry() {
        ServerAttributeDefinition ato = attributes.create(
                "ATOStatus", "ATO status", null, ServerAttributeType.TEXT, false, null, null, "alice");
        attributes.setValues(webId, ato.getId(), List.of("Authorized"), "alice");

        attributes.setValues(webId, ato.getId(), List.of(), "alice");

        assertThat(directory.valuesOf(webDn, "ATOStatus")).isEmpty();
        assertThat(attributes.valuesFor(webId).get(ato.getId())).isEmpty();
    }

    /** A boolean is TRUE in a directory, and absent rather than FALSE when it is not set. */
    @Test
    void aBooleanIsWrittenTheWayADirectoryHoldsOne() {
        ServerAttributeDefinition audited = attributes.create(
                "icAudited", "In scope for audit", null, ServerAttributeType.BOOLEAN, false, null, null, "alice");

        attributes.setValues(webId, audited.getId(), List.of("true"), "alice");
        assertThat(directory.valuesOf(webDn, "icAudited")).containsExactly("TRUE");
        assertThat(attributes.valuesFor(webId)).containsEntry(audited.getId(), List.of("true"));

        attributes.setValues(webId, audited.getId(), List.of("false"), "alice");
        assertThat(directory.valuesOf(webDn, "icAudited")).isEmpty();
        assertThat(attributes.valuesFor(webId).get(audited.getId())).isEmpty();
    }

    /** However the directory cased it, the switch reads the same. */
    @Test
    void aBooleanIsReadWhateverCaseTheDirectoryUsed() {
        ServerAttributeDefinition audited = attributes.create(
                "icAudited", "In scope for audit", null, ServerAttributeType.BOOLEAN, false, null, null, "alice");

        directory.modify(webDn, "icAudited", "true");
        assertThat(attributes.valuesFor(webId)).containsEntry(audited.getId(), List.of("true"));

        directory.modify(webDn, "icAudited", "FALSE");
        assertThat(attributes.valuesFor(webId).get(audited.getId())).isEmpty();
    }

    /**
     * An attribute the sweep also caches is a column on the row as well as an attribute on
     * the entry, so the row is brought back in step rather than waiting for the next sweep.
     */
    @Test
    void theCachedCopyOfTheEntryCatchesUpImmediately() {
        ServerAttributeDefinition ato = attributes.create(
                "ATOStatus", "ATO status", null, ServerAttributeType.TEXT, false, null, null, "alice");

        attributes.setValues(webId, ato.getId(), List.of("Authorized"), "alice");

        assertThat(servers.findById(webId).orElseThrow().getAtoStatus()).isEqualTo("Authorized");
    }

    @Test
    void whatWasWrittenIsRecordedAgainstTheServer() {
        ServerAttributeDefinition ato = attributes.create(
                "ATOStatus", "ATO status", null, ServerAttributeType.TEXT, false, null, null, "bob");

        attributes.setValues(webId, ato.getId(), List.of("Authorized"), "bob");
        attributes.setValues(webId, ato.getId(), List.of(), "bob");

        List<AuditEvent> history = auditFor(webId);
        assertThat(history).extracting(AuditEvent::getAction)
                .startsWith(AuditAction.ATTRIBUTE_CLEARED, AuditAction.ATTRIBUTE_SET);
        assertThat(history.get(1).getSummary()).contains("Set ATO status (ATOStatus) in the directory to Authorized");
        assertThat(history.get(1).getActor()).isEqualTo("bob");
        assertThat(history.get(1).getTarget()).isEqualTo("ATOStatus");
    }

    @Test
    void settingWhatItAlreadyHoldsWritesNothingAndRecordsNothing() {
        ServerAttributeDefinition ato = attributes.create(
                "ATOStatus", "ATO status", null, ServerAttributeType.TEXT, false, null, null, "alice");
        attributes.setValues(webId, ato.getId(), List.of("Authorized"), "alice");
        long recorded = auditFor(webId).size();

        attributes.setValues(webId, ato.getId(), List.of("Authorized"), "alice");

        assertThat(auditFor(webId)).hasSize((int) recorded);
    }

    // ---------------------------------------------------------------------------------
    // What may be defined, and what may be written
    // ---------------------------------------------------------------------------------

    @Test
    void anAttributeIsManagedOnceAndUnderOneName() {
        attributes.create("ATOStatus", "ATO status", null, ServerAttributeType.TEXT, false, null, null, "alice");

        assertThatThrownBy(() -> attributes.create(
                        "atostatus", "Something else", null, ServerAttributeType.TEXT, false, null, null, "alice"))
                .isInstanceOf(ContactAlreadyExistsException.class)
                .hasMessageContaining("already managed");
        assertThatThrownBy(() -> attributes.create(
                        "lifeCycleStatus", "ATO status", null, ServerAttributeType.TEXT, false, null, null, "alice"))
                .isInstanceOf(ContactAlreadyExistsException.class)
                .hasMessageContaining("already an attribute called");
    }

    @Test
    void somethingThatIsNotAnAttributeNameIsRefused() {
        assertThatThrownBy(() -> attributes.create(
                        "", "Nothing", null, ServerAttributeType.TEXT, false, null, null, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Name the directory attribute");
        assertThatThrownBy(() -> attributes.create(
                        "not an attribute", "Nothing", null, ServerAttributeType.TEXT, false, null, null, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an attribute name");
    }

    /** Named after the attribute unless somebody says otherwise. */
    @Test
    void theLabelDefaultsToTheAttribute() {
        ServerAttributeDefinition definition = attributes.create(
                "lifeCycleStatus", " ", null, ServerAttributeType.TEXT, false, null, null, "alice");

        assertThat(definition.getName()).isEqualTo("lifeCycleStatus");
    }

    @Test
    void aDropDownNeedsSomethingToChooseFromAndRefusesAnythingElse() {
        assertThatThrownBy(() -> attributes.create(
                        "ATOStatus", "ATO status", null, ServerAttributeType.CHOICE, false, List.of(), null, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one value");

        ServerAttributeDefinition ato = attributes.create(
                "ATOStatus", "ATO status", null, ServerAttributeType.CHOICE, false, List.of("Authorized"), null,
                "alice");
        assertThatThrownBy(() -> attributes.setValues(webId, ato.getId(), List.of("Whatever"), "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not one of the values");
        assertThat(directory.valuesOf(webDn, "ATOStatus")).as("nothing was written").isEmpty();
    }

    @Test
    void aBooleanIsAlwaysOneValueAndASingleValuedAttributeRefusesASecond() {
        ServerAttributeDefinition audited = attributes.create(
                "icAudited", "In scope for audit", null, ServerAttributeType.BOOLEAN, true, null, null, "alice");
        assertThat(audited.isMultiValued()).isFalse();

        ServerAttributeDefinition ato = attributes.create(
                "ATOStatus", "ATO status", null, ServerAttributeType.TEXT, false, null, null, "alice");
        assertThatThrownBy(() -> attributes.setValues(webId, ato.getId(), List.of("One", "Two"), "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holds one value");
    }

    /**
     * Retiring a definition stops it being offered here. The directory goes on holding what
     * it held: those values were never this application's to remove.
     */
    @Test
    void takingAnAttributeOffTheListLeavesTheDirectoryAlone() {
        ServerAttributeDefinition ato = attributes.create(
                "ATOStatus", "ATO status", null, ServerAttributeType.TEXT, false, null, null, "alice");
        attributes.setValues(webId, ato.getId(), List.of("Authorized"), "alice");

        attributes.delete(ato.getId(), "alice");

        assertThat(definitions.findById(ato.getId())).isEmpty();
        assertThat(directory.valuesOf(webDn, "ATOStatus")).containsExactly("Authorized");
    }

    private List<AuditEvent> auditFor(Long serverId) {
        return auditRepository
                .findBySubjectTypeAndSubjectIdOrderByOccurredAtDescIdDesc(
                        OwnerType.SERVER, serverId, PageRequest.of(0, 20))
                .getContent();
    }
}
