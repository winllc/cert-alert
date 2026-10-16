package com.winllc.certalert.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The ens-audit/1 serialisation and hash rule, against the shared test vector. */
class AuditFormatTest {

    /** The event body every ENS product must produce for this event, byte for byte. */
    static final String VECTOR_BODY = "{\"schema\":\"ens-audit/1\",\"id\":\"00000000-0000-4000-8000-000000000001\","
            + "\"seq\":1,\"time\":\"2026-01-01T00:00:00.000Z\",\"product\":\"test\",\"version\":\"dev\",\"host\":\"h\","
            + "\"installationId\":null,\"category\":\"system\",\"action\":\"system.started\",\"outcome\":\"success\","
            + "\"severity\":\"info\",\"actor\":{\"type\":\"system\",\"id\":null,\"name\":null,\"roles\":[]},"
            + "\"source\":null,\"target\":null,\"reason\":null,\"details\":{},\"requestId\":null,\"session\":null,"
            + "\"prevHash\":\"0000000000000000000000000000000000000000000000000000000000000000\"}";

    /** From the kit's vector.txt. */
    static final String VECTOR_HASH = "90b915788e0c86d867ee7f9786404f333744acaf88803a244651156792ca300d";

    private static AuditRecord vectorEvent() {
        return AuditRecord.of(AuditRecord.Category.SYSTEM, "system.started")
                .id("00000000-0000-4000-8000-000000000001")
                .time(Instant.parse("2026-01-01T00:00:00Z"))
                .actor(AuditRecord.Actor.system(null))
                .markContextCaptured();
    }

    @Test
    void theSerialiserProducesTheVectorBodyExactly() {
        String body = AuditFormat.body(1, vectorEvent(), new AuditFormat.Origin("test", "dev", "h", null),
                AuditFormat.GENESIS);
        assertThat(body).isEqualTo(VECTOR_BODY);
    }

    @Test
    void theVectorBodyHashesToTheVectorHash() {
        assertThat(AuditFormat.hash(AuditFormat.GENESIS, VECTOR_BODY)).isEqualTo(VECTOR_HASH);
    }

    @Test
    void theLineIsTheBodyWithItsHashLastAndBothCanBeReadBack() {
        String line = AuditFormat.line(VECTOR_BODY, VECTOR_HASH);
        assertThat(line).endsWith(",\"prevHash\":\"" + AuditFormat.GENESIS + "\",\"hash\":\"" + VECTOR_HASH + "\"}");
        assertThat(AuditFormat.bodyOf(line)).isEqualTo(VECTOR_BODY);
        assertThat(AuditFormat.hashOf(line)).isEqualTo(VECTOR_HASH);
        assertThat(AuditFormat.prevHashOf(VECTOR_BODY)).isEqualTo(AuditFormat.GENESIS);
    }

    @Test
    void everyFieldIsPresentInOrderAndStringsAreEscaped() {
        AuditRecord event = AuditRecord.of(AuditRecord.Category.AUTHENTICATION, "auth.login")
                .id("id-1")
                .time(Instant.parse("2026-10-16T02:10:11.123456Z"))
                .outcome(AuditRecord.Outcome.FAILURE)
                .actor(AuditRecord.Actor.anonymous("j\"doe"))
                .source(new AuditRecord.Source("10.1.2.3", "Agent\n1"))
                .target("user", "jdoe", "Jane Doe")
                .reason("bad credentials")
                .detail("method", "ldap")
                .detail("attempts", 3)
                .detail("locked", false)
                .markContextCaptured();
        String body = AuditFormat.body(7, event, new AuditFormat.Origin("certalert", "1.4.0", "host", "inst"), "a".repeat(64));
        assertThat(body).startsWith("{\"schema\":\"ens-audit/1\",\"id\":\"id-1\",\"seq\":7,"
                + "\"time\":\"2026-10-16T02:10:11.123Z\",\"product\":\"certalert\",\"version\":\"1.4.0\",\"host\":\"host\","
                + "\"installationId\":\"inst\",\"category\":\"authentication\",\"action\":\"auth.login\","
                + "\"outcome\":\"failure\",\"severity\":\"warning\","
                + "\"actor\":{\"type\":\"anonymous\",\"id\":\"j\\\"doe\",\"name\":null,\"roles\":[]},"
                + "\"source\":{\"ip\":\"10.1.2.3\",\"userAgent\":\"Agent\\n1\"},"
                + "\"target\":{\"type\":\"user\",\"id\":\"jdoe\",\"name\":\"Jane Doe\"},"
                + "\"reason\":\"bad credentials\",\"details\":{\"method\":\"ldap\",\"attempts\":3,\"locked\":false},"
                + "\"requestId\":null,\"session\":null,\"prevHash\":\"");
    }

    @Test
    void secretsAreRedactedByNameAndTokensByValue() {
        AuditRecord event = AuditRecord.of(AuditRecord.Category.CONFIGURATION, "settings.changed")
                .detail("password", "hunter2")
                .detail("smtp.password.new", "hunter3")
                .detail("apiKey", "k-123")
                .detail("privateKey", "-----BEGIN")
                .detail("sessionId", "ABC")
                .detail("emergencyCode", "123456")
                .detail("pin", "0000")
                .detail("licenseText", "-----BEGIN ENS LICENSE")
                .detail("note", "uses ensat_abcdefABCDEF0123456789_- here")
                .detail("leadDays.new", 14)
                .detail("name", "Splunk");
        assertThat(event.details())
                .containsEntry("password", AuditRecord.REDACTED)
                .containsEntry("smtp.password.new", AuditRecord.REDACTED)
                .containsEntry("apiKey", AuditRecord.REDACTED)
                .containsEntry("privateKey", AuditRecord.REDACTED)
                .containsEntry("sessionId", AuditRecord.REDACTED)
                .containsEntry("emergencyCode", AuditRecord.REDACTED)
                .containsEntry("pin", AuditRecord.REDACTED)
                .containsEntry("licenseText", AuditRecord.REDACTED)
                .containsEntry("note", "uses [redacted] here")
                .containsEntry("leadDays.new", 14)
                .containsEntry("name", "Splunk");
    }

    @Test
    void longStringsAreCutTo1000Characters() {
        AuditRecord event = AuditRecord.of(AuditRecord.Category.DATA, "x.y").detail("summary", "z".repeat(5000));
        assertThat((String) event.details().get("summary")).hasSize(1000);
    }

    @Test
    void severityDefaultsFollowTheSpec() {
        assertThat(AuditRecord.of(AuditRecord.Category.DATA, "a.b").severity()).isEqualTo(AuditRecord.Severity.INFO);
        assertThat(AuditRecord.of(AuditRecord.Category.CONFIGURATION, "a.b").severity())
                .isEqualTo(AuditRecord.Severity.NOTICE);
        assertThat(AuditRecord.of(AuditRecord.Category.DATA, "a.b").outcome(AuditRecord.Outcome.DENIED).severity())
                .isEqualTo(AuditRecord.Severity.WARNING);
        assertThat(List.of(AuditRecord.Severity.values()).stream().map(AuditRecord.Severity::syslog))
                .containsExactly(6, 5, 4, 1);
    }

    @Test
    void csvCellsAreQuotedAndFormulaSafe() {
        assertThat(AuditAdminController.csv("plain")).isEqualTo("plain");
        assertThat(AuditAdminController.csv("a,b")).isEqualTo("\"a,b\"");
        assertThat(AuditAdminController.csv("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(AuditAdminController.csv("=SUM(A1)")).isEqualTo("'=SUM(A1)");
    }
}
