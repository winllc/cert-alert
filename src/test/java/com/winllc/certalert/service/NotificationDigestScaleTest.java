package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.config.NotificationProperties;
import com.winllc.certalert.domain.Notification;
import com.winllc.certalert.domain.NotificationKind;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.NotificationRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The round-up against a cache of {@value #TOTAL} certificates, shaped the way an old
 * directory is: most of it expired long ago, a good part renewed, and a few thousand
 * certificates genuinely about to expire - every one of which has to be reported.
 *
 * <ul>
 *   <li>{@value #UPCOMING} people with one certificate expiring in the next two days;
 *   <li>{@value #RENEWED} people whose certificate expiring in the same days has already
 *       been renewed, two certificates each - left out by the query;
 *   <li>{@value #LAPSED} people whose only certificate expired inside the look-back -
 *       reported as expired;
 *   <li>and the rest, {@value #DEPARTED_PEOPLE} people holding {@value #DEPARTED_EACH}
 *       certificates each that expired between 100 days and three years ago - outside the
 *       look-back, and so never read at all.
 * </ul>
 *
 * <p>Read as the round-up used to read it - one query, expired first, stopping at 10,000 -
 * the old certificates filled the read and none of the upcoming ones was reached. The rows
 * go in with plain JDBC batches, and the read is paged small, so it crosses a page
 * boundary dozens of times.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class NotificationDigestScaleTest {

    static final int UPCOMING = 5_000;
    static final int RENEWED = 2_500;
    static final int LAPSED = 500;
    static final int DEPARTED_PEOPLE = 8_950;
    static final int DEPARTED_EACH = 10;
    static final int TOTAL = UPCOMING + 2 * RENEWED + LAPSED + DEPARTED_PEOPLE * DEPARTED_EACH;

    /** Well clear of anything the sequences hand out during a test run. */
    private static final long BASE_ID = 900_000_000L;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationSettingsService settings;

    @Autowired
    private NotificationProperties properties;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private DirectoryUserRepository users;

    @Autowired
    private DirectoryServerRepository servers;

    @Autowired
    private JdbcTemplate jdbc;

    private int pageSizeBefore;

    @BeforeEach
    void setUp() {
        notifications.deleteAll();
        servers.deleteAll();
        users.deleteAll();
        pageSizeBefore = properties.getDigest().getPageSize();
        properties.getDigest().setPageSize(250);
    }

    @AfterEach
    void tearDown() {
        properties.getDigest().setPageSize(pageSizeBefore);
        jdbc.update("delete from notification");
        // Certificates go with their owner (ON DELETE CASCADE).
        jdbc.update("delete from directory_user where id >= ?", BASE_ID);
    }

    @Test
    void everyCertificateAboutToExpireIsReportedAmongAHundredThousand(CapturedOutput output) {
        Instant now = Instant.now();
        assertThat(settings.leadDays()).as("the upcoming certificates expire inside two days").isGreaterThanOrEqualTo(2);
        Seed seed = new Seed(now);

        Set<String> upcoming = new HashSet<>();
        for (int i = 0; i < UPCOMING; i++) {
            String mail = seed.person("upcoming" + i);
            upcoming.add(mail);
            seed.certificate("CN=upcoming" + i, now.minus(Duration.ofDays(360)), soon(now, i), "EXPIRING_SOON");
        }
        Set<String> renewed = new HashSet<>();
        for (int i = 0; i < RENEWED; i++) {
            renewed.add(seed.person("renewed" + i));
            seed.certificate("CN=renewed" + i, now.minus(Duration.ofDays(360)), soon(now, i), "EXPIRING_SOON");
            seed.certificate("CN=renewed" + i, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(364)), "VALID");
        }
        Set<String> lapsed = new HashSet<>();
        for (int i = 0; i < LAPSED; i++) {
            lapsed.add(seed.person("lapsed" + i));
            seed.certificate("CN=lapsed" + i, now.minus(Duration.ofDays(400)),
                    now.minus(Duration.ofDays(10)).minus(Duration.ofMinutes(i)), "EXPIRED");
        }
        Set<String> departed = new HashSet<>();
        for (int i = 0; i < DEPARTED_PEOPLE; i++) {
            departed.add(seed.person("departed" + i));
            for (int j = 0; j < DEPARTED_EACH; j++) {
                Instant expired = now.minus(Duration.ofDays(100 + (i * DEPARTED_EACH + j) % 1000));
                seed.certificate("CN=departed" + i + ",OU=" + j, expired.minus(Duration.ofDays(365)), expired, "EXPIRED");
            }
        }
        seed.flush();
        assertThat(jdbc.queryForObject("select count(*) from cached_certificate where id >= ?", Long.class, BASE_ID))
                .isEqualTo(TOTAL);

        long started = System.nanoTime();
        NotificationService.DigestResult result = notificationService.digest();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result.certificates()).isEqualTo(UPCOMING + LAPSED);
        assertThat(result.peopleTold()).isEqualTo(UPCOMING + LAPSED);

        Set<String> told = notifications.findAll().stream()
                .filter(notification -> notification.getKind() == NotificationKind.EXPIRY_DIGEST)
                .map(Notification::getRecipientAddress)
                .collect(Collectors.toSet());
        // Every one about to expire, and every one that expired inside the look-back.
        assertThat(told).containsAll(upcoming).containsAll(lapsed);
        // Nobody whose certificate was renewed, nor anybody outside the look-back.
        assertThat(told).doesNotContainAnyElementsOf(renewed).doesNotContainAnyElementsOf(departed);
        assertThat(output.getOut()).doesNotContain("Raise cert-alert.notifications.digest.scan-limit");
        assertThat(took).as("one round-up over %d certificates", TOTAL).isLessThan(Duration.ofMinutes(2));
    }

    /** Thirty seconds apart from an hour from now: all inside two days. */
    private static Instant soon(Instant now, int i) {
        return now.plus(Duration.ofHours(1)).plus(Duration.ofSeconds(30L * i));
    }

    /** Batches the rows, so a hundred thousand of them take seconds rather than minutes. */
    private final class Seed {

        private final Instant now;
        private final List<Object[]> people = new ArrayList<>();
        private final List<Object[]> certificates = new ArrayList<>();
        private long nextUser = BASE_ID;
        private long nextCertificate = BASE_ID;

        Seed(Instant now) {
            this.now = now;
        }

        String person(String uid) {
            nextUser++;
            String mail = uid + "@example.gov";
            people.add(new Object[] {nextUser, "uid=" + uid + ",ou=scale", uid, uid, mail, mail,
                    Timestamp.from(now), Timestamp.from(now)});
            if (people.size() == 1_000) {
                flushPeople();
            }
            return mail;
        }

        void certificate(String subject, Instant notBefore, Instant notAfter, String status) {
            nextCertificate++;
            certificates.add(new Object[] {nextCertificate, nextUser, "%064x".formatted(nextCertificate),
                    Long.toHexString(nextCertificate), subject, "CN=Scale CA", Timestamp.from(notBefore),
                    Timestamp.from(notAfter), status, Timestamp.from(now)});
            if (certificates.size() == 5_000) {
                flushPeople();
                flushCertificates();
            }
        }

        void flush() {
            flushPeople();
            flushCertificates();
        }

        private void flushPeople() {
            if (!people.isEmpty()) {
                jdbc.batchUpdate("""
                        insert into directory_user (id, dn, uid, display_name, email, ic_email,
                                                    first_seen_at, last_synced_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """, people);
                people.clear();
            }
        }

        private void flushCertificates() {
            if (!certificates.isEmpty()) {
                jdbc.batchUpdate("""
                        insert into cached_certificate (id, user_id, sha256_fingerprint, serial_number,
                                                        subject_dn, issuer_dn, not_before, not_after,
                                                        status, cached_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, certificates);
                certificates.clear();
            }
        }
    }
}
