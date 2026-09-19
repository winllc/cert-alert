package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.ServerAttributeDefinition;
import com.winllc.certalert.domain.ServerAttributeType;
import com.winllc.certalert.repository.AuditEventRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.ServerAttributeDefinitionRepository;
import com.winllc.certalert.repository.ServerAttributeValueRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The attributes a deployment keeps about its servers on top of the directory's schema.
 *
 * <p>The interesting part is what a definition may become once servers hold values for it:
 * the values are what bound it, and every refusal has to say what is in the way.
 */
@SpringBootTest
@ActiveProfiles("test")
class ServerAttributeTest {

    @Autowired
    private ServerAttributeService attributes;

    @Autowired
    private ServerAttributeDefinitionRepository definitions;

    @Autowired
    private ServerAttributeValueRepository values;

    @Autowired
    private DirectoryServerRepository servers;

    @Autowired
    private AuditEventRepository auditRepository;

    private Long webId;
    private Long dbId;

    @BeforeEach
    void seed() {
        values.deleteAll();
        definitions.deleteAll();
        auditRepository.deleteAll();
        servers.deleteAll();

        webId = servers.save(server("cn=web01,ou=servers", "web01")).getId();
        dbId = servers.save(server("cn=db01,ou=servers", "db01")).getId();
    }

    @Test
    void anAttributeIsDefinedOnceAndAppliesToEveryServer() {
        ServerAttributeDefinition environment = attributes.create(
                "Environment", "Where it runs", ServerAttributeType.CHOICE, false,
                List.of("Production", "Staging"), null, "alice");

        assertThat(environment.getId()).isNotNull();
        assertThat(environment.getOptions()).containsExactly("Production", "Staging");
        assertThat(attributes.list()).extracting(ServerAttributeDefinition::getName).containsExactly("Environment");

        attributes.setValues(webId, environment.getId(), List.of("Production"), "alice");

        assertThat(attributes.valuesFor(webId)).containsEntry(environment.getId(), List.of("Production"));
        assertThat(attributes.valuesFor(dbId)).as("the other server holds nothing yet").isEmpty();
    }

    @Test
    void refusesTwoAttributesOfTheSameNameAndOneWithNone() {
        attributes.create("Environment", null, ServerAttributeType.TEXT, false, null, null, "alice");

        assertThatThrownBy(() -> attributes.create(
                        "environment", null, ServerAttributeType.TEXT, false, null, null, "alice"))
                .isInstanceOf(ContactAlreadyExistsException.class);
        assertThatThrownBy(() -> attributes.create(
                        "  ", null, ServerAttributeType.TEXT, false, null, null, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a name");
    }

    @Test
    void aDropDownNeedsSomethingToChooseFrom() {
        assertThatThrownBy(() -> attributes.create(
                        "Environment", null, ServerAttributeType.CHOICE, false, List.of(), null, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one value");
    }

    /** A second value would have to contradict the first. */
    @Test
    void aBooleanIsAlwaysOneValue() {
        ServerAttributeDefinition audited = attributes.create(
                "In scope for audit", null, ServerAttributeType.BOOLEAN, true, null, null, "alice");

        assertThat(audited.isMultiValued()).isFalse();
        assertThatThrownBy(() -> attributes.setValues(webId, audited.getId(), List.of("true", "false"), "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** "No" is the absence of the attribute, so it is not stored and reads as unset. */
    @Test
    void aBooleanTurnedOffHoldsNothing() {
        ServerAttributeDefinition audited = attributes.create(
                "In scope for audit", null, ServerAttributeType.BOOLEAN, false, null, null, "alice");

        attributes.setValues(webId, audited.getId(), List.of("true"), "alice");
        assertThat(attributes.valuesFor(webId)).containsEntry(audited.getId(), List.of("true"));

        attributes.setValues(webId, audited.getId(), List.of("false"), "alice");
        assertThat(attributes.valuesFor(webId)).isEmpty();
    }

    @Test
    void severalValuesAreKeptInOrderAndWithoutRepeats() {
        ServerAttributeDefinition tags = attributes.create(
                "Tags", null, ServerAttributeType.TEXT, true, null, null, "alice");

        List<String> saved = attributes.setValues(
                webId, tags.getId(), List.of("payroll", "tier-1", "payroll", "  "), "alice");

        assertThat(saved).containsExactly("payroll", "tier-1");
        assertThat(attributes.valuesFor(webId)).containsEntry(tags.getId(), List.of("payroll", "tier-1"));
    }

    @Test
    void aSingleValuedAttributeRefusesASecondValue() {
        ServerAttributeDefinition owner = attributes.create(
                "Budget code", null, ServerAttributeType.TEXT, false, null, null, "alice");

        assertThatThrownBy(() -> attributes.setValues(webId, owner.getId(), List.of("AB-1", "AB-2"), "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holds one value");
    }

    @Test
    void aDropDownRefusesSomethingNotOnTheList() {
        ServerAttributeDefinition environment = attributes.create(
                "Environment", null, ServerAttributeType.CHOICE, false, List.of("Production", "Staging"), null,
                "alice");

        assertThatThrownBy(() -> attributes.setValues(webId, environment.getId(), List.of("Sandbox"), "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not one of the values");
    }

    /** Setting it again replaces what was there rather than adding to it. */
    @Test
    void settingAnAttributeReplacesWhatItHeld() {
        ServerAttributeDefinition tags = attributes.create(
                "Tags", null, ServerAttributeType.TEXT, true, null, null, "alice");
        attributes.setValues(webId, tags.getId(), List.of("payroll", "tier-1"), "alice");

        attributes.setValues(webId, tags.getId(), List.of("tier-2"), "alice");

        assertThat(attributes.valuesFor(webId)).containsEntry(tags.getId(), List.of("tier-2"));
        assertThat(values.findByServerIdOrderByPositionAscIdAsc(webId)).hasSize(1);
    }

    @Test
    void clearingAnAttributeLeavesNothingBehind() {
        ServerAttributeDefinition tags = attributes.create(
                "Tags", null, ServerAttributeType.TEXT, true, null, null, "alice");
        attributes.setValues(webId, tags.getId(), List.of("payroll"), "alice");

        attributes.setValues(webId, tags.getId(), List.of(), "alice");

        assertThat(attributes.valuesFor(webId)).isEmpty();
        assertThat(auditFor(webId)).extracting(AuditEvent::getAction)
                .containsExactly(AuditAction.ATTRIBUTE_CLEARED, AuditAction.ATTRIBUTE_SET);
        assertThat(auditFor(webId).getFirst().getSummary()).contains("Cleared Tags").contains("payroll");
    }

    @Test
    void whatWasSetIsRecordedAgainstTheServer() {
        ServerAttributeDefinition environment = attributes.create(
                "Environment", null, ServerAttributeType.CHOICE, false, List.of("Production"), null, "alice");

        attributes.setValues(webId, environment.getId(), List.of("Production"), "bob");

        List<AuditEvent> history = auditFor(webId);
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getAction()).isEqualTo(AuditAction.ATTRIBUTE_SET);
        assertThat(history.getFirst().getSummary()).isEqualTo("Set Environment to Production");
        assertThat(history.getFirst().getActor()).isEqualTo("bob");
        assertThat(history.getFirst().getTarget()).isEqualTo("Environment");
    }

    /** Setting it to what it already says is not a change, and nothing is recorded. */
    @Test
    void settingTheSameValueAgainRecordsNothing() {
        ServerAttributeDefinition tags = attributes.create(
                "Tags", null, ServerAttributeType.TEXT, true, null, null, "alice");
        attributes.setValues(webId, tags.getId(), List.of("payroll"), "alice");

        attributes.setValues(webId, tags.getId(), List.of("payroll"), "alice");

        assertThat(auditFor(webId)).hasSize(1);
    }

    // ---------------------------------------------------------------------------------
    // What a definition may become, once servers hold values for it
    // ---------------------------------------------------------------------------------

    @Test
    void anOptionStillInUseCannotBeTakenAway() {
        ServerAttributeDefinition environment = attributes.create(
                "Environment", null, ServerAttributeType.CHOICE, false, List.of("Production", "Staging"), null,
                "alice");
        attributes.setValues(webId, environment.getId(), List.of("Staging"), "alice");

        assertThatThrownBy(() -> attributes.update(
                        environment.getId(), "Environment", null, ServerAttributeType.CHOICE, false,
                        List.of("Production"), null, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Staging");

        // Adding one is never a problem.
        attributes.update(environment.getId(), "Environment", null, ServerAttributeType.CHOICE, false,
                List.of("Production", "Staging", "Development"), null, "alice");
        assertThat(attributes.get(environment.getId()).getOptions()).hasSize(3);
    }

    @Test
    void severalCannotBecomeOneWhileAServerHoldsTwo() {
        ServerAttributeDefinition tags = attributes.create(
                "Tags", null, ServerAttributeType.TEXT, true, null, null, "alice");
        attributes.setValues(webId, tags.getId(), List.of("payroll", "tier-1"), "alice");

        assertThatThrownBy(() -> attributes.update(
                        tags.getId(), "Tags", null, ServerAttributeType.TEXT, false, null, null, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than one value");

        attributes.setValues(webId, tags.getId(), List.of("payroll"), "alice");
        attributes.update(tags.getId(), "Tags", null, ServerAttributeType.TEXT, false, null, null, "alice");
        assertThat(attributes.get(tags.getId()).isMultiValued()).isFalse();
    }

    @Test
    void theKindCannotChangeUnderneathValuesRecordedAsSomethingElse() {
        ServerAttributeDefinition environment = attributes.create(
                "Environment", null, ServerAttributeType.TEXT, false, null, null, "alice");
        attributes.setValues(webId, environment.getId(), List.of("Production"), "alice");

        assertThatThrownBy(() -> attributes.update(
                        environment.getId(), "Environment", null, ServerAttributeType.CHOICE, false,
                        List.of("Production"), null, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot change kind");

        attributes.setValues(webId, environment.getId(), List.of(), "alice");
        attributes.update(environment.getId(), "Environment", null, ServerAttributeType.CHOICE, false,
                List.of("Production"), null, "alice");
        assertThat(attributes.get(environment.getId()).getType()).isEqualTo(ServerAttributeType.CHOICE);
    }

    /** Retiring an attribute takes its values, which mean nothing without it. */
    @Test
    void retiringAnAttributeTakesWhatWasHeldForIt() {
        ServerAttributeDefinition tags = attributes.create(
                "Tags", null, ServerAttributeType.TEXT, true, null, null, "alice");
        attributes.setValues(webId, tags.getId(), List.of("payroll"), "alice");
        attributes.setValues(dbId, tags.getId(), List.of("payroll"), "alice");

        attributes.delete(tags.getId(), "alice");

        assertThat(definitions.findById(tags.getId())).isEmpty();
        assertThat(values.count()).isZero();
        assertThat(servers.findById(webId)).as("the server is the directory's").isPresent();
    }

    /** A pruned server takes its own values with it, like everything else hanging off it. */
    @Test
    void aServerLeavingTakesItsValuesWithIt() {
        ServerAttributeDefinition tags = attributes.create(
                "Tags", null, ServerAttributeType.TEXT, true, null, null, "alice");
        attributes.setValues(webId, tags.getId(), List.of("payroll"), "alice");
        attributes.setValues(dbId, tags.getId(), List.of("ledger"), "alice");

        servers.deleteById(webId);

        assertThat(values.findByServerIdOrderByPositionAscIdAsc(webId)).isEmpty();
        assertThat(attributes.valuesFor(dbId)).containsEntry(tags.getId(), List.of("ledger"));
    }

    private List<AuditEvent> auditFor(Long serverId) {
        return auditRepository
                .findBySubjectTypeAndSubjectIdOrderByOccurredAtDescIdDesc(
                        OwnerType.SERVER, serverId, PageRequest.of(0, 20))
                .getContent();
    }

    private DirectoryServer server(String dn, String commonName) {
        DirectoryServer server = new DirectoryServer(dn);
        server.setCommonName(commonName);
        server.markSynced(Instant.now());
        return server;
    }
}
