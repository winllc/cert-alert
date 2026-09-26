package com.winllc.certalert.revocation;

import com.winllc.certalert.config.RevocationProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fetches certificate revocation lists, and holds on to them.
 *
 * <p>Holding on to them is the point. A CRL is one authority's list of every serial it has
 * revoked, so one download answers for every certificate that authority issued - and a
 * directory of a hundred thousand entries is issued by a handful of authorities. Fetching
 * per certificate would be a hundred thousand downloads of the same few files.
 *
 * <p>A CRL says when it will next be published, and that is what decides how long it is
 * kept: reusing one past its {@code nextUpdate} would mean missing the revocation it was
 * published to announce. Where it says nothing, or says something implausible, the
 * configured ceiling applies.
 */
@Component
public class CrlStore {

    private static final Logger log = LoggerFactory.getLogger(CrlStore.class);

    /** The attribute a directory publishes a CRL in; the option asks for it as bytes. */
    private static final String CRL_ATTRIBUTE = "certificateRevocationList;binary";

    private final RevocationProperties properties;
    private final HttpClient http;
    /**
     * By distribution point. The value is the answer rather than the list, so that a second
     * caller arriving while the first is still downloading waits for it instead of starting
     * a download of its own.
     */
    private final Map<String, CompletableFuture<Held>> held = new ConcurrentHashMap<>();

    public CrlStore(RevocationProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                // A CRL distribution point that redirects is ordinary; one that redirects
                // to somewhere else's idea of a CRL is caught by the signature check.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** A CRL and what is known about it. */
    public record Fetched(X509CRL crl, String url, boolean verified) {}

    /**
     * The first of these distribution points that answers.
     *
     * @param urls the certificate's distribution points, in its own order
     * @param issuer the certificate of whoever issued it, for checking the signature, or
     *     null where it is not known
     */
    public Optional<Fetched> fetch(List<String> urls, X509Certificate issuer, Instant now) {
        for (String url : urls) {
            Optional<Fetched> crl = fetchOne(url, issuer, now);
            if (crl.isPresent()) {
                return crl;
            }
        }
        return Optional.empty();
    }

    /** Forgets everything held, so the next check fetches again. */
    public void clear() {
        held.clear();
    }

    /**
     * One download per distribution point, however many ask for it at once.
     *
     * <p>Whoever gets there first puts an unfinished answer in the map and goes to fetch;
     * everybody else finds it and waits on it. Looking, missing and then fetching - which
     * is the obvious way to write this - means every worker that looks before the first
     * one finishes goes and fetches the same list, and a handful of downloads for the whole
     * directory becomes a handful per batch. That is the property this cache exists for.
     *
     * <p>A fetch that fails takes its answer back out of the map, so the next certificate
     * to name that distribution point tries again rather than inheriting the failure.
     */
    private Optional<Fetched> fetchOne(String url, X509Certificate issuer, Instant now) {
        Held current = awaitHeld(url);
        if (current != null && current.usableAt(now)) {
            return Optional.of(verify(current.crl(), url, issuer));
        }

        CompletableFuture<Held> mine = new CompletableFuture<>();
        CompletableFuture<Held> inFlight = held.putIfAbsent(url, mine);
        if (inFlight != null && inFlight != mine) {
            // Somebody else is fetching it, or has. Either way, theirs is the answer.
            Held theirs = join(inFlight);
            return theirs == null || !theirs.usableAt(now)
                    ? Optional.empty()
                    : Optional.of(verify(theirs.crl(), url, issuer));
        }

        try {
            byte[] bytes = read(url);
            X509CRL crl = (X509CRL) CertificateFactory.getInstance("X.509")
                    .generateCRL(new ByteArrayInputStream(bytes));
            mine.complete(new Held(crl, keepUntil(crl, now)));
            log.info("Fetched a CRL from {}: {} entries, next update {}",
                    url,
                    crl.getRevokedCertificates() == null ? 0 : crl.getRevokedCertificates().size(),
                    crl.getNextUpdate());
            return Optional.of(verify(crl, url, issuer));
        } catch (IOException | GeneralSecurityException | RuntimeException | NamingException e) {
            // One unreachable distribution point is not the end of the check; the next one
            // in the certificate may answer, and an unanswered question reads as unknown.
            held.remove(url, mine);
            mine.complete(null);
            log.info("Could not fetch a CRL from {}: {}", url, describe(e));
            return Optional.empty();
        }
    }

    /** What is held for this point, waiting for a fetch already under way. */
    private Held awaitHeld(String url) {
        CompletableFuture<Held> holding = held.get(url);
        return holding == null ? null : join(holding);
    }

    private Held join(CompletableFuture<Held> holding) {
        try {
            return holding.join();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether this list is provably the authority's own.
     *
     * <p>Not fatal either way - what to do about an unverified CRL is the caller's decision
     * and a configured one - but it is recorded, because a list fetched over plain HTTP
     * from a URL the certificate names is only as trustworthy as whatever answered.
     */
    private Fetched verify(X509CRL crl, String url, X509Certificate issuer) {
        if (issuer == null) {
            return new Fetched(crl, url, false);
        }
        try {
            crl.verify(issuer.getPublicKey());
            return new Fetched(crl, url, true);
        } catch (GeneralSecurityException e) {
            log.warn("The CRL at {} is not signed by {}: {}",
                    url, issuer.getSubjectX500Principal().getName(), describe(e));
            return new Fetched(crl, url, false);
        }
    }

    private byte[] read(String url) throws IOException, NamingException {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ldap://") || lower.startsWith("ldaps://")) {
            return readFromDirectory(url);
        }
        return readOverHttp(url);
    }

    private byte[] readOverHttp(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(properties.getReadTimeout())
                .header("Accept", "application/pkix-crl, application/x-pkcs7-crl, */*")
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        byte[] body = response.body();
        if (body.length > properties.getMaxCrlBytes()) {
            throw new IOException("CRL at " + url + " is " + body.length + " bytes, over the configured limit");
        }
        return body;
    }

    /**
     * A distribution point inside the directory, which is where a PKI that has no route to
     * the internet publishes its lists. The URL names the host, the entry and the
     * attribute, so it is handed to JNDI whole rather than picked apart.
     */
    private byte[] readFromDirectory(String url) throws NamingException, IOException {
        Hashtable<String, Object> environment = new Hashtable<>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        environment.put(Context.PROVIDER_URL, url);
        // Anonymous: a CRL is published to be read, and this application's directory
        // credentials are for its own directory, which this need not be.
        environment.put(Context.SECURITY_AUTHENTICATION, "none");
        environment.put("java.naming.ldap.attributes.binary", "certificateRevocationList");
        environment.put("com.sun.jndi.ldap.connect.timeout",
                String.valueOf(properties.getConnectTimeout().toMillis()));
        environment.put("com.sun.jndi.ldap.read.timeout", String.valueOf(properties.getReadTimeout().toMillis()));

        DirContext context = new InitialDirContext(environment);
        try {
            Attributes attributes = context.getAttributes("", new String[] {CRL_ATTRIBUTE, "certificateRevocationList"});
            Attribute attribute = attributes.get(CRL_ATTRIBUTE);
            if (attribute == null) {
                attribute = attributes.get("certificateRevocationList");
            }
            if (attribute == null || attribute.size() == 0) {
                throw new IOException("No certificateRevocationList at " + url);
            }
            Object value = attribute.get(0);
            if (!(value instanceof byte[] bytes)) {
                throw new IOException("The CRL at " + url + " did not come back as bytes");
            }
            if (bytes.length > properties.getMaxCrlBytes()) {
                throw new IOException("CRL at " + url + " is " + bytes.length + " bytes, over the configured limit");
            }
            return bytes;
        } finally {
            context.close();
        }
    }

    /**
     * How long this list may be reused. Its own {@code nextUpdate} decides, because that is
     * when the authority said the next one would exist; the configured ceiling stops a list
     * with a distant or missing next update from being held indefinitely.
     */
    private Instant keepUntil(X509CRL crl, Instant now) {
        Instant ceiling = now.plus(properties.getCrlCacheTtl());
        if (crl.getNextUpdate() == null) {
            return ceiling;
        }
        Instant next = crl.getNextUpdate().toInstant();
        return next.isBefore(ceiling) ? next : ceiling;
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private record Held(X509CRL crl, Instant until) {

        boolean usableAt(Instant now) {
            return now.isBefore(until);
        }
    }
}
