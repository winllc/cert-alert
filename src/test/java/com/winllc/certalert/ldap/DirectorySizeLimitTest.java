package com.winllc.certalert.ldap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.SizeLimitExceededException;
import org.springframework.ldap.core.support.LdapContextSource;

/**
 * A directory that stops a search at its size limit. A sweep that took the entries it got
 * for all there are would stop alerting on everybody past the limit, and prune them later
 * as no longer seen - so a search cut short fails instead, unless {@code count-limit} asked
 * for fewer entries on purpose.
 */
class DirectorySizeLimitTest {

    private static final String BASE = "dc=example,dc=test";
    private static final String PEOPLE = "ou=people," + BASE;
    private static final int LIMIT = 5;
    private static final int PEOPLE_COUNT = 12;

    private InMemoryDirectoryServer server;
    private LdapContextSource contextSource;

    @BeforeEach
    void start() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
        config.setSchema(null);
        config.setMaxSizeLimit(LIMIT);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("test", 0));
        server = new InMemoryDirectoryServer(config);
        server.startListening();
        server.add(new Entry(BASE, new Attribute("objectClass", "top", "domain"), new Attribute("dc", "example")));
        server.add(new Entry(PEOPLE, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "people")));
        for (int i = 0; i < PEOPLE_COUNT; i++) {
            server.add(new Entry("uid=person" + i + "," + PEOPLE,
                    new Attribute("objectClass", "top", "icOrgPerson"),
                    new Attribute("uid", "person" + i),
                    new Attribute("mail", "person" + i + "@example.gov")));
        }

        contextSource = new LdapContextSource();
        contextSource.setUrl("ldap://localhost:" + server.getListenPort());
        contextSource.afterPropertiesSet();
    }

    @AfterEach
    void stop() {
        server.shutDown(true);
    }

    @Test
    void anUnpagedSearchCutShortFails() {
        LdapDirectoryClient client = client(false, 500, 0);

        assertThatThrownBy(() -> client.forEachUser(entry -> {}))
                .isInstanceOf(SizeLimitExceededException.class);
    }

    @Test
    void aPagedSearchCutShortFails() {
        // One page larger than the limit: the server stops partway through it.
        LdapDirectoryClient client = client(true, 100, 0);

        assertThatThrownBy(() -> client.forEachUser(entry -> {}))
                .isInstanceOf(SizeLimitExceededException.class);
    }

    @Test
    void aPagedSearchWithinTheLimitReadsEverybody() {
        List<LdapUserEntry> read = new ArrayList<>();

        int count = client(true, 2, 0).forEachUser(read::add);

        assertThat(count).isEqualTo(PEOPLE_COUNT);
        assertThat(read).hasSize(PEOPLE_COUNT);
    }

    /** A count limit set on purpose is expected to stop the search, and is not a failure. */
    @Test
    void aDeliberateCountLimitStillEndsQuietly() {
        List<LdapUserEntry> read = new ArrayList<>();

        int count = client(false, 500, 3).forEachUser(read::add);

        assertThat(count).isEqualTo(3);
        assertThat(read).hasSize(3);
    }

    private LdapDirectoryClient client(boolean paged, int pageSize, long countLimit) {
        LdapProperties properties = new LdapProperties();
        properties.setPaged(paged);
        properties.setPageSize(pageSize);
        properties.setCountLimit(countLimit);
        properties.getUser().setSearchBase(PEOPLE);
        return new LdapDirectoryClient(contextSource, properties, new DirectoryEntryMapper(properties));
    }
}
