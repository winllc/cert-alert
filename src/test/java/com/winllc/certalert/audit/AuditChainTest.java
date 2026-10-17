package com.winllc.certalert.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.repository.AuditEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The integrity chain in the store: gapless seq, each event chained to the one before,
 * tampering detected at the seq it was done to, pruning reported as an anchor, and history
 * from before the format migrated into the chain.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditChainTest {

    @Autowired
    private AuditLog audit;

    @Autowired
    private AuditStore store;

    @Autowired
    private AuditVerifier verifier;

    @Autowired
    private AuditRetention retention;

    @Autowired
    private EnsAuditProperties properties;

    @Autowired
    private AuditEventRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;

    @BeforeEach
    void startFromAnEmptyTrail() {
        repository.deleteAll();
    }

    @AfterEach
    void keepEverything() {
        properties.setRetentionDays(0);
    }

    private static AuditRecord event(String action) {
        return AuditRecord.of(AuditRecord.Category.SYSTEM, action).actor(AuditRecord.Actor.system("test"));
    }

    @Test
    void eventsAreNumberedWithoutGapsAndChained() {
        long head = store.headSeq();
        audit.record(event("test.one"));
        audit.recordAll(List.of(event("test.two"), event("test.three")));
        audit.recordQuietly(event("test.four"));

        List<StoredEvent> written = store.after(head, 100);
        assertThat(written).extracting(StoredEvent::seq).containsExactly(head + 1, head + 2, head + 3, head + 4);
        for (int i = 1; i < written.size(); i++) {
            String body = AuditFormat.bodyOf(written.get(i).line());
            assertThat(AuditFormat.prevHashOf(body)).isEqualTo(AuditFormat.hashOf(written.get(i - 1).line()));
            assertThat(AuditFormat.hash(AuditFormat.prevHashOf(body), body)).isEqualTo(AuditFormat.hashOf(written.get(i).line()));
        }
        assertThat(written.getFirst().line()).startsWith("{\"schema\":\"ens-audit/1\",");
        assertThat(written.getFirst().line()).contains("\"product\":\"certalert\"");

        AuditVerifier.Result result = verifier.verify();
        assertThat(result.intact()).as(result.summary()).isTrue();
        assertThat(result.lastSeq()).isEqualTo(head + 4);
    }

    @Test
    void anEventWhoseTransactionRollsBackIsNotWrittenAndTakesNoSeq() {
        long head = store.headSeq();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            audit.record(event("test.rolled-back"));
            status.setRollbackOnly();
        });
        assertThat(store.headSeq()).isEqualTo(head);
        audit.record(event("test.kept"));
        assertThat(store.after(head, 10)).extracting(StoredEvent::seq, StoredEvent::action)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(head + 1, "test.kept"));
    }

    @Test
    void tamperingWithAStoredEventIsDetectedAtItsSeq() {
        long head = store.headSeq();
        for (int i = 0; i < 5; i++) {
            audit.record(event("test.tamper").detail("n", i));
        }
        long victim = head + 3;
        String original = jdbc.queryForObject("select line from audit_event where seq = ?", String.class, victim);
        try {
            jdbc.update("update audit_event set line = ? where seq = ?",
                    original.replace("\"n\":2", "\"n\":9"), victim);

            AuditVerifier.Result result = verifier.verify();
            assertThat(result.intact()).isFalse();
            assertThat(result.brokenSeq()).isEqualTo(victim);
            assertThat(result.summary()).contains("Broken at seq " + victim);
        } finally {
            jdbc.update("update audit_event set line = ? where seq = ?", original, victim);
        }
        assertThat(verifier.verify().intact()).isTrue();
    }

    @Test
    void deletingAnEventInTheMiddleIsDetected() {
        long head = store.headSeq();
        for (int i = 0; i < 4; i++) {
            audit.record(event("test.delete"));
        }
        jdbc.update("delete from audit_event where seq = ?", head + 2);

        AuditVerifier.Result result = verifier.verify();
        assertThat(result.intact()).isFalse();
        assertThat(result.brokenSeq()).isEqualTo(head + 3);
        repository.deleteAll();
    }

    @Test
    void verificationAfterPruningReportsTheAnchor() {
        Instant old = Instant.now().minus(Duration.ofDays(40));
        long head = store.headSeq();
        audit.record(event("test.old").time(old));
        audit.record(event("test.old").time(old.plusSeconds(1)));
        audit.record(event("test.old").time(old.plusSeconds(2)));
        audit.record(event("test.recent"));
        audit.record(event("test.recent"));

        properties.setRetentionDays(30);
        assertThat(retention.prune()).isEqualTo(3);

        assertThat(store.firstSeq()).isEqualTo(head + 4);
        AuditVerifier.Result result = verifier.verify();
        assertThat(result.intact()).as(result.summary()).isTrue();
        assertThat(result.anchorSeq()).isEqualTo(head + 4);
        assertThat(result.anchorPrevHash()).isNotEqualTo(AuditFormat.GENESIS);
        assertThat(result.summary()).contains("anchored at seq " + (head + 4));

        StoredEvent pruned = store.after(head + 5, 10).getFirst();
        assertThat(pruned.action()).isEqualTo("audit.pruned");
        assertThat(pruned.line()).contains("\"count\":3", "\"fromSeq\":" + (head + 1), "\"toSeq\":" + (head + 3));
    }

    @Test
    void pruningNeverRemovesTheNewestEvent() {
        Instant old = Instant.now().minus(Duration.ofDays(40));
        long head = store.headSeq();
        audit.record(event("test.old").time(old));
        audit.record(event("test.old").time(old));
        properties.setRetentionDays(30);

        assertThat(retention.prune()).isEqualTo(1);
        assertThat(store.firstSeq()).isEqualTo(head + 2);
    }

    @Test
    void theOldRetentionSettingStillWorksAndKeepingForeverIsTheDefault() {
        assertThat(retention.retentionDays()).isZero();
        assertThat(retention.describe()).contains("forever");
    }

    @Test
    void historyFromBeforeTheFormatIsChainedInTimeOrderKeepingItsTime() {
        Instant then = Instant.parse("2025-03-04T05:06:07.890Z");
        jdbc.update("insert into audit_event (id, occurred_at, action, subject_type, subject_id, subject_dn, "
                        + "subject_name, actor, summary) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                900_000_001L, java.sql.Timestamp.from(then.plusSeconds(60)), "CONTACT_ADDED", "SERVER", 42L,
                "cn=web01,ou=servers", "web01", "alice@example.gov", "Added alice as a point of contact");
        jdbc.update("insert into audit_event (id, occurred_at, action, subject_type, subject_id, subject_dn, "
                        + "subject_name, actor, summary) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                900_000_002L, java.sql.Timestamp.from(then), "ENTRY_DISCOVERED", "SERVER", 42L,
                "cn=web01,ou=servers", "web01", "sync", "Discovered, publishing 1 certificate");
        long head = store.headSeq();

        audit.chainHistory();

        List<StoredEvent> migrated = store.after(head, 10);
        assertThat(migrated).extracting(StoredEvent::action).containsExactly("entry.discovered", "contact.added");
        assertThat(migrated.getFirst().line())
                .contains("\"time\":\"2025-03-04T05:06:07.890Z\"", "\"migrated\":true",
                        "\"actor\":{\"type\":\"system\",\"id\":\"sync\"", "\"category\":\"data\"",
                        "\"target\":{\"type\":\"server\",\"id\":\"cn=web01,ou=servers\",\"name\":\"web01\"}");
        assertThat(migrated.get(1).line()).contains("\"actor\":{\"type\":\"user\",\"id\":\"alice@example.gov\"");
        assertThat(verifier.verify().intact()).isTrue();
        // And it still reads as that server's history.
        assertThat(repository.countBySubjectTypeAndSubjectId(com.winllc.certalert.domain.OwnerType.SERVER, 42L))
                .isEqualTo(2);
    }
}
