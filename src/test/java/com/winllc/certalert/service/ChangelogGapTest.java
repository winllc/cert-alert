package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.ChangelogCursor;
import com.winllc.certalert.repository.ChangelogCursorRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.support.EmbeddedDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What happens when the directory discards changes the connector never reached.
 *
 * <p>The in-memory server trims its changelog to a fixed size, exactly as a real one does
 * with age - so this produces a genuine gap rather than a simulated one.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChangelogGapTest {

    /** Small enough that a handful of writes push history past the connector. */
    private static final int RETAINED_CHANGES = 3;

    private static EmbeddedDirectory directory;

    @Autowired
    private ChangelogConnector connector;

    @Autowired
    private ChangelogCursorStore cursorStore;

    @Autowired
    private ChangelogCursorRepository cursorRepository;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory(RETAINED_CHANGES);
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
        registry.add("cert-alert.ldap.changelog.enabled", () -> "true");
        registry.add("cert-alert.ldap.changelog.auto-start", () -> "false");
        // These cases prime the connector with a poll of their own; the first-run import is
        // ChangelogFirstRunTest's subject, and a sweep here would populate the cache the
        // changes are supposed to be what fills.
        registry.add("cert-alert.ldap.changelog.full-sync-on-first-run", () -> "false");
        registry.add("cert-alert.ldap.changelog.filter", () -> "(objectClass=changeLogEntry)");
    }

    @Test
    void aTrimmedChangelogIsNoticedAndRecoveredWithASweep() {
        serverRepository.deleteAll();
        userRepository.deleteAll();
        cursorRepository.deleteAll();

        // Start following, then fall behind far enough that the directory drops what we
        // have not read.
        connector.pollOnce();
        long startedAt = cursorStore.find().orElseThrow().getLastChangeNumber();

        for (int i = 0; i < RETAINED_CHANGES + 3; i++) {
            directory.addUser("gap%d".formatted(i), "Gap Person %d".formatted(i), "gap%d@example.gov".formatted(i));
        }

        connector.pollOnce();

        ChangelogCursor cursor = cursorStore.find().orElseThrow();
        assertThat(cursor.getGapsDetected()).isEqualTo(1);
        // It jumped to where the directory's history now begins rather than sitting behind it.
        assertThat(cursor.getLastChangeNumber()).isGreaterThan(startedAt);
        assertThat(cursor.getFirstAvailableNumber()).isNotNull();

        // The sweep it ran is what makes the cache right again: every person the directory
        // holds is cached, including the ones whose changes were lost.
        assertThat(userRepository.count()).isEqualTo(RETAINED_CHANGES + 3);
    }
}
