package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectorySpecifications;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.support.EmbeddedDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scrapes what {@code scripts/generate-directory-data.sh} produces.
 *
 * <p>That script fills the stand-in directory docker-compose.yml brings up, and a demo
 * that comes up with empty tables is worse than no demo. This is what notices when the two
 * drift apart: the generator writing an attribute name the mapping does not read, or no
 * longer producing one of the certificate states the tables filter on.
 *
 * <p>Skipped where the script cannot run - it needs bash and openssl.
 */
@SpringBootTest
@ActiveProfiles("test")
class GeneratedDirectoryTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final Path SCRIPT = Path.of("scripts/generate-directory-data.sh");
    private static final int USERS = 24;
    private static final int SERVERS = 12;

    private static Path workingDirectory;
    private static EmbeddedDirectory directory;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void generateAndLoad() throws IOException, InterruptedException {
        assumeTrue(Files.isRegularFile(SCRIPT), "generator script not present");
        String bash = findBash();
        assumeTrue(bash != null, "bash not on the path");
        assumeTrue(WINDOWS || canRun("openssl"), "openssl not on the path");

        workingDirectory = Files.createTempDirectory("generated-directory");
        Path ldif = workingDirectory.resolve("directory.ldif");
        ProcessBuilder builder = new ProcessBuilder(
                        bash,
                        forBash(SCRIPT),
                        "--users",
                        String.valueOf(USERS),
                        "--servers",
                        String.valueOf(SERVERS),
                        "--base",
                        EmbeddedDirectory.BASE_DN,
                        "--out",
                        forBash(ldif))
                .redirectErrorStream(true);
        // Git Bash rewrites any argument starting with a slash into a Windows path, which
        // turns openssl's -subj "/CN=..." into a file name. Everything else it rewrites is
        // a real path, and wants rewriting, so only the subjects are excluded.
        builder.environment().put("MSYS2_ARG_CONV_EXCL", "/CN");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(5, TimeUnit.MINUTES))
                .as("the generator finished, output was:%n%s", output)
                .isTrue();
        assertThat(process.exitValue())
                .as("the generator exited cleanly, output was:%n%s", output)
                .isZero();

        directory = new EmbeddedDirectory();
        directory.importLdif(ldif);
    }

    @AfterAll
    static void stopDirectory() throws IOException {
        if (directory != null) {
            directory.close();
        }
        if (workingDirectory != null) {
            try (var paths = Files.walk(workingDirectory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
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
    void scrape() {
        // The in-memory database is shared with the other sync tests, so this starts from
        // an empty one rather than trusting whatever ran before it.
        serverRepository.deleteAll();
        userRepository.deleteAll();
        syncService.syncUsers();
        syncService.syncServers();
    }

    @Test
    void scrapesEverythingTheGeneratorWrote() {
        assertThat(userRepository.count()).isEqualTo(USERS);
        assertThat(serverRepository.count()).isEqualTo(SERVERS);

        DirectoryUser alice = userRepository
                .findByDn("uid=alice," + EmbeddedDirectory.PEOPLE_DN)
                .orElseThrow();
        assertThat(alice.getDisplayName()).isEqualTo("Alice Archer");
        assertThat(alice.getEmail()).isEqualTo("alice@intelink.ic.gov");
        assertThat(alice.getUid()).isEqualTo("alice");
        assertThat(alice.getIcMember()).isTrue();
        assertThat(alice.getCountryOfAffiliation()).isEqualTo("USA");
        assertThat(alice.getDutyOrganization()).isEqualTo("Example Agency");
        assertThat(alice.getEmployeeType()).isEqualTo("Civilian");
        assertThat(alice.getCertificateCount()).isEqualTo(1);

        DirectoryServer server = serverRepository
                .findByDn("cn=web01," + EmbeddedDirectory.SERVERS_DN)
                .orElseThrow();
        assertThat(server.getServerUrl()).isEqualTo("https://web01.example.ic.gov");
        assertThat(server.getAtoStatus()).isEqualTo("Authorized");
        assertThat(server.getLifeCycleStatus()).isEqualTo("Production");
        assertThat(server.getIcServerAddress()).isNotBlank();
    }

    /**
     * Every state the tables filter on has to be in the data, or the demo shows an
     * interface whose filters all return everything.
     */
    @Test
    void coversEveryCertificateState() {
        assertThat(userStatuses())
                .contains(
                        CertificateStatus.VALID,
                        CertificateStatus.EXPIRING_SOON,
                        CertificateStatus.EXPIRED,
                        CertificateStatus.NONE);
        assertThat(serverStatuses()).contains(CertificateStatus.VALID, CertificateStatus.EXPIRED);
    }

    /**
     * Points of contact are written both as an address and as a person's name, because the
     * specification defines serverPOC as a name and directories in the field do both. The
     * join has to land on the same person either way.
     */
    @Test
    void writesPointsOfContactBothWaysRound() {
        List<String> pocs = transactionTemplate.execute(status -> serverRepository.findAll().stream()
                .flatMap(server -> server.getServerPocs().stream())
                .distinct()
                .toList());

        assertThat(pocs).anyMatch(poc -> poc.contains("@")).anyMatch(poc -> !poc.contains("@"));
        // Lowercased on both sides of the join, which is what lets it match at all.
        assertThat(pocs).allMatch(poc -> poc.equals(poc.toLowerCase(Locale.ROOT)));

        DirectoryUser alice = userRepository
                .findByDn("uid=alice," + EmbeddedDirectory.PEOPLE_DN)
                .orElseThrow();
        Set<String> identifiers = userRepository.findIdentifiersById(alice.getId());
        assertThat(identifiers).contains("alice@intelink.ic.gov", "alice archer", "alice");

        List<DirectoryServer> hers =
                serverRepository.findAll(DirectorySpecifications.pointOfContactAnyOf(identifiers));
        assertThat(hers).as("the servers alice is the point of contact for").isNotEmpty();
    }

    private Set<CertificateStatus> userStatuses() {
        return userRepository.findAll().stream()
                .map(DirectoryUser::getCertificateStatus)
                .collect(Collectors.toSet());
    }

    private Set<CertificateStatus> serverStatuses() {
        return serverRepository.findAll().stream()
                .map(DirectoryServer::getCertificateStatus)
                .collect(Collectors.toSet());
    }

    /**
     * The bash to run the generator with.
     *
     * <p>On Windows that has to be Git Bash, found by where it installs rather than on the
     * path: the {@code bash.exe} in System32 is WSL's, which sees a different filesystem
     * and none of Git Bash's tools, openssl among them.
     */
    private static String findBash() {
        if (!WINDOWS) {
            return canRun("bash") ? "bash" : null;
        }
        return Stream.of(System.getenv("ProgramW6432"), System.getenv("ProgramFiles"),
                        System.getenv("LOCALAPPDATA"))
                .filter(Objects::nonNull)
                .flatMap(root -> Stream.of(
                        Path.of(root, "Git", "bin", "bash.exe"),
                        Path.of(root, "Programs", "Git", "bin", "bash.exe")))
                .filter(Files::isRegularFile)
                .map(Path::toString)
                .findFirst()
                .orElse(null);
    }

    /** Bash reads a backslash as an escape, so hand it forward slashes; Git Bash takes C:/ paths. */
    private static String forBash(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static boolean canRun(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(30, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
