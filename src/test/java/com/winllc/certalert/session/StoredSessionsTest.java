package com.winllc.certalert.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.audit.AuditStore;
import com.winllc.certalert.audit.StoredEvent;
import com.winllc.certalert.security.EnsSecurityProperties;
import com.winllc.certalert.security.SessionPolicy;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.support.EmbeddedDirectory;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.session.Session;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Sessions in the database ({@code cert-alert.session.store=jdbc}): they outlast the replica
 * that made them, and the session controls still hold - across replicas, which share only
 * the database.
 */
@SpringBootTest(properties = {
    "cert-alert.session.store=jdbc",
    "ENS_SESSION_IDLE_TIMEOUT=20m"
})
@ActiveProfiles("test")
class StoredSessionsTest {

    private static EmbeddedDirectory directory;
    private static boolean synced;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private EnsSecurityProperties properties;

    @Autowired
    @SuppressWarnings("rawtypes")
    private FindByIndexNameSessionRepository sessions;

    @Autowired
    private SessionRepositoryFilter<?> sessionFilter;

    @Autowired
    private SessionSweeper sweeper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditStore auditStore;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
        directory.addUser("sam", "Sam Stored", "sam@example.gov");
    }

    @AfterAll
    static void stopDirectory() {
        directory.close();
    }

    @DynamicPropertySource
    static void directoryProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
    }

    @BeforeEach
    void setUp() {
        if (!synced) {
            syncService.syncUsers();
            synced = true;
        }
    }

    @AfterEach
    void restore() {
        properties.getSession().setMaxPerUser(0);
        jdbc.update("DELETE FROM SPRING_SESSION");
    }

    /** A replica: its own filter chain, and nothing in common with another but the database. */
    private MockMvc replica() {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(sessionFilter)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** A browser: the session cookie it was last given. */
    private static final class Browser {
        private Cookie cookie;

        MvcResult perform(MockMvc replica, MockHttpServletRequestBuilder request) throws Exception {
            if (cookie != null) {
                request.cookie(cookie);
            }
            MvcResult result = replica.perform(request).andReturn();
            remember(result.getResponse());
            return result;
        }

        void remember(MockHttpServletResponse response) {
            Cookie given = response.getCookie("SESSION");
            if (given != null) {
                cookie = given;
            }
        }

        String sessionId() {
            return new String(Base64.getDecoder().decode(cookie.getValue()));
        }
    }

    private Browser signIn(MockMvc replica) throws Exception {
        Browser browser = new Browser();
        MvcResult login = replica.perform(formLogin().user("sam").password("password")).andReturn();
        browser.remember(login.getResponse());
        assertThat(browser.cookie).as("a SESSION cookie").isNotNull();
        // The first signed-in request is where the session policy takes it on.
        assertThat(browser.perform(replica, get("/api/v1/stats/users")).getResponse().getStatus()).isEqualTo(200);
        return browser;
    }

    @Test
    void aSessionIsServedByAnotherReplica() throws Exception {
        Browser browser = signIn(replica());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Integer.class)).isEqualTo(1);
        assertThat(browser.perform(replica(), get("/api/v1/stats/users")).getResponse().getStatus())
                .isEqualTo(200);
        Session stored = sessions.findById(browser.sessionId());
        assertThat(stored).isNotNull();
        assertThat((Object) stored.getAttribute(SessionPolicy.SIGNED_IN_AT)).isInstanceOf(Instant.class);
        // Its stored expiry is a little beyond the idle limit, which the policy enforces itself.
        assertThat(stored.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(25));
    }

    @Test
    void overTheLimitTheOldestSessionEndsWhicheverReplicaServesIt() throws Exception {
        properties.getSession().setMaxPerUser(1);
        long head = auditStore.headSeq();
        Browser first = signIn(replica());
        Browser second = signIn(replica());

        assertThat(first.perform(replica(), get("/api/v1/stats/users")).getResponse().getStatus()).isEqualTo(401);
        assertThat(second.perform(replica(), get("/api/v1/stats/users")).getResponse().getStatus()).isEqualTo(200);
        assertThat(expiries(head)).singleElement().satisfies(line -> assertThat(line)
                .contains("\"reason\":\"concurrent\""));
    }

    @Test
    void anIdleSessionIsRefusedAndRecordedOnItsNextRequest() throws Exception {
        Browser browser = signIn(replica());
        long head = auditStore.headSeq();
        Session stored = sessions.findById(browser.sessionId());
        stored.setAttribute("com.winllc.certalert.security.SessionPolicy.lastSeen", Instant.now().minus(Duration.ofMinutes(21)));
        save(stored);

        assertThat(browser.perform(replica(), get("/api/v1/stats/users")).getResponse().getStatus()).isEqualTo(401);
        assertThat(expiries(head)).singleElement().satisfies(line -> assertThat(line)
                .contains("\"reason\":\"idle\""));
    }

    @Test
    void anExpiredSessionIsClearedAndRecordedAsIdle() throws Exception {
        Browser browser = signIn(replica());
        long head = auditStore.headSeq();
        jdbc.update("UPDATE SPRING_SESSION SET EXPIRY_TIME = ? WHERE SESSION_ID = ?",
                Instant.now().minus(Duration.ofMinutes(1)).toEpochMilli(), browser.sessionId());

        assertThat(sweeper.sweepOnce()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Integer.class)).isZero();
        assertThat(expiries(head)).singleElement().satisfies(line -> assertThat(line)
                .contains("\"reason\":\"idle\""));
    }

    @SuppressWarnings("unchecked")
    private void save(Session session) {
        sessions.save(session);
    }

    private List<String> expiries(long head) {
        return auditStore.after(head, 1000).stream()
                .filter(event -> "auth.session-expired".equals(event.action()))
                .map(StoredEvent::line)
                .toList();
    }
}
