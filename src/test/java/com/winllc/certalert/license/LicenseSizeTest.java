package com.winllc.certalert.license;

import static org.assertj.core.api.Assertions.assertThat;

import com.enssolutions.license.LicenseManager;
import com.winllc.certalert.alert.AlertDispatcher;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.service.DirectorySyncResult;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestLicenses;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The license is sized in directory users. A sweep adds new people only while the license
 * allows one more (up to 10% over, with a warning), and never removes anybody for it.
 */
@SpringBootTest
@ActiveProfiles("test")
class LicenseSizeTest {

    private static EmbeddedDirectory directory;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    @Autowired
    private LicenseManager licenses;

    @Autowired
    private Licensing licensing;

    @MockitoBean
    private AlertDispatcher alertDispatcher;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
        for (int i = 1; i <= 15; i++) {
            directory.addUser("person" + i, "Person " + i, "person" + i + "@example.gov");
        }
    }

    @AfterAll
    static void stopDirectory() {
        if (directory != null) {
            directory.close();
        }
    }

    @DynamicPropertySource
    static void directoryProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
    }

    @BeforeEach
    void resetDatabase() {
        serverRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void restore() {
        TestLicenses.restoreFull(licenses);
    }

    @Test
    void aSweepStopsAddingPeopleOnceTheLicenseIsUsedUp() {
        // Ten users, and 10% over that is eleven.
        TestLicenses.install(licenses, TestLicenses.license().quantity(10).sign());

        DirectorySyncResult result = syncService.syncUsers();

        assertThat(userRepository.count()).isEqualTo(11);
        assertThat(result.entriesCreated()).isEqualTo(11);
        assertThat(licensing.usageMessage(userRepository.count()))
                .isEqualTo("11 of 10 directory users: over the licensed size. Update the license to stay covered.");
        assertThat(licensing.mayAdd(userRepository.count())).isFalse();
    }

    @Test
    void nobodyIsRemovedWhenTheLicenseShrinks() {
        syncService.syncUsers();
        assertThat(userRepository.count()).isEqualTo(15);

        TestLicenses.install(licenses, TestLicenses.license().quantity(5).sign());
        syncService.syncUsers();

        assertThat(userRepository.count()).isEqualTo(15);
    }

    @Test
    void theRestAreAddedOnceTheLicenseAllows() {
        TestLicenses.install(licenses, TestLicenses.license().quantity(5).sign());
        syncService.syncUsers();
        assertThat(userRepository.count()).isEqualTo(5);

        TestLicenses.install(licenses, TestLicenses.license().quantity(100).sign());
        syncService.syncUsers();

        assertThat(userRepository.count()).isEqualTo(15);
    }
}
