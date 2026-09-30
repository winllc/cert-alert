package com.winllc.certalert.demo;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * The directory a demo indexes: its own, held in memory, thrown away with the process.
 *
 * <p>A demo has to stand on its own. Pointed at somebody's real directory it would publish
 * every entry in it to anyone who opened the page, and pointed at nothing it is an empty
 * table demonstrating an empty table. So it brings a directory with it, and what the
 * application then does is exactly what it does anywhere else - sweep an LDAP server over
 * the wire, cache what it finds, and report on it. Nothing about the demo is a stub.
 *
 * <p>It binds the address the application is already configured to talk to, taken from
 * {@code spring.ldap.urls}, so there is one place that says where the directory is rather
 * than two that have to agree.
 *
 * <p>Switched off by {@code cert-alert.demo.generate-directory: false}, for a demo shown
 * against a directory that is already running - the compose file's, or a generated one
 * loaded into a real server. Starting a second one on the address the first is answering
 * on is the only other thing it could do.
 */
@Component
@Conditional(DemoMode.On.class)
@ConditionalOnProperty(prefix = "cert-alert.demo", name = "generate-directory", matchIfMissing = true)
public class DemoDirectoryServer implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DemoDirectoryServer.class);

    private final InMemoryDirectoryServer server;

    public DemoDirectoryServer(
            DemoProperties demo,
            @Value("${spring.ldap.urls:ldap://localhost:18390}") List<String> urls,
            @Value("${spring.ldap.base:dc=example,dc=test}") String baseDn) {

        int port = portOf(urls.getFirst());
        try {
            InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(baseDn);
            // The FSD schema is not a stock one - serverPOC, icEmail and the rest are
            // defined by the specification this application reads, not by a schema file
            // shipped with an LDAP server - so entries are accepted as written.
            config.setSchema(null);
            config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("demo", port));
            this.server = new InMemoryDirectoryServer(config);
            this.server.startListening();

            // The sign-in accounts come from the seed; the crowd around them is generated.
            List<Entry> seeded = DemoAccountSeed.read(baseDn, demo.getPassword());
            DemoDirectoryData data = new DemoDirectoryData(
                    baseDn, new DemoCertificates(), Instant.now(), demo.getSeed(), demo.getPassword(), seeded);
            List<Entry> entries = data.entries(demo.getPeople(), demo.getServers());
            for (Entry entry : entries) {
                server.add(entry);
            }
            log.info("Demo: serving {} entries on port {}, {} of them sign-in accounts from {}",
                    entries.size(), port, seeded.size(), DemoAccounts.LDIF);
        } catch (LDAPException e) {
            throw new IllegalStateException(
                    "Could not start the demo's directory on " + urls.getFirst()
                            + ". Something else may already be listening there; "
                            + "spring.ldap.urls chooses the address.",
                    e);
        }
    }

    /**
     * The port the application has been told the directory is on.
     *
     * <p>A URL without one would mean 389, which a container does not run as root to bind
     * and which would more likely be somebody's real directory - so it is refused rather
     * than guessed at.
     */
    private static int portOf(String url) {
        int port = URI.create(url).getPort();
        if (port <= 0) {
            throw new IllegalStateException(
                    "The demo serves its own directory, so spring.ldap.urls has to name a port to serve it on: "
                            + url);
        }
        return port;
    }

    @Override
    public void destroy() {
        server.shutDown(true);
    }
}
