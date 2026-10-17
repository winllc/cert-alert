package com.winllc.certalert.revocation;

import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPURL;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.util.ssl.HostNameSSLSocketVerifier;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

/**
 * Fetches revocation data - a CRL, an OCSP response - within limits.
 *
 * <p>Every address it is asked for came out of a certificate, so every one is treated as a
 * stranger's: checked against the {@link FetchPolicy} before anything is sent, and again at
 * every redirect, which may only lead to http or https. The body is counted as it arrives
 * and dropped the moment it passes the size limit - never read whole and measured after -
 * and the whole fetch, redirects included, has a deadline as well as the per-wait
 * timeouts, so a server trickling a byte at a time cannot hold a worker indefinitely.
 *
 * <p>Two of these exist with different timeouts: the scheduled check, which can afford to
 * wait for a slow authority, and sign-in, which has somebody waiting on it. Each check says
 * which one it uses; neither changes anything process-wide.
 */
public final class RevocationFetcher {

    /** Redirects followed before giving up: a distribution point that moved, not a maze. */
    static final int MAX_REDIRECTS = 3;

    private static final String CRL_ATTRIBUTE = "certificateRevocationList";

    private final FetchPolicy policy;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration maxTime;
    private final long maxBytes;
    private final HttpClient http;

    public RevocationFetcher(
            FetchPolicy policy, Duration connectTimeout, Duration readTimeout, Duration maxTime, long maxBytes) {
        this.policy = policy;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.maxTime = maxTime;
        this.maxBytes = maxBytes;
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                // Followed here rather than by the client, so each hop is checked first.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public FetchPolicy policy() {
        return policy;
    }

    /** What is published at this address, over HTTP or from a directory. */
    public byte[] get(String url, String accept) throws IOException {
        URI uri = parse(url);
        policy.check(uri);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("ldap") || scheme.equals("ldaps")) {
            return readFromDirectory(url, uri);
        }
        long deadline = System.nanoTime() + maxTime.toNanos();
        URI current = uri;
        for (int hop = 0; ; hop++) {
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(remaining(deadline, readTimeout))
                    .header("Accept", accept)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = send(request, deadline);
            if (isRedirect(response.statusCode())) {
                if (hop >= MAX_REDIRECTS) {
                    throw new IOException("More than " + MAX_REDIRECTS + " redirects from " + url);
                }
                current = redirectFrom(current, response);
                continue;
            }
            return bodyOf(current, response);
        }
    }

    /**
     * Posts a request - an OCSP one - and reads the answer. A redirect is not followed: an
     * OCSP request is a POST, and resending it somewhere else on a server's say-so is
     * exactly what a responder has no reason to ask for.
     */
    public byte[] post(String url, String contentType, byte[] body, String accept) throws IOException {
        URI uri = parse(url);
        policy.checkRedirect(uri);
        long deadline = System.nanoTime() + maxTime.toNanos();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(remaining(deadline, readTimeout))
                .header("Content-Type", contentType)
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> response = send(request, deadline);
        if (isRedirect(response.statusCode())) {
            throw new IOException("HTTP " + response.statusCode() + " from " + uri + ": redirects are not followed");
        }
        return bodyOf(uri, response);
    }

    private HttpResponse<byte[]> send(HttpRequest request, long deadline) throws IOException {
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, this::subscriber);
        try {
            return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("Fetching " + request.uri() + " took longer than " + maxTime);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + request.uri(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(cause == null ? e.toString() : cause.toString(), cause);
        }
    }

    /**
     * Counts the body as it arrives. A declared length over the limit is refused before a
     * byte of it is read; an undeclared one is refused when it gets there.
     */
    private HttpResponse.BodySubscriber<byte[]> subscriber(HttpResponse.ResponseInfo info) {
        if (isRedirect(info.statusCode())) {
            return HttpResponse.BodySubscribers.replacing(new byte[0]);
        }
        OptionalLong declared = info.headers().firstValueAsLong("Content-Length");
        if (declared.isPresent() && declared.getAsLong() > maxBytes) {
            return new Limited(maxBytes, "the response declares " + declared.getAsLong()
                    + " bytes, over the limit of " + maxBytes);
        }
        return new Limited(maxBytes, null);
    }

    private URI redirectFrom(URI current, HttpResponse<?> response) throws IOException {
        String location = response.headers()
                .firstValue("Location")
                .orElseThrow(() -> new IOException("HTTP " + response.statusCode() + " from " + current
                        + " with nowhere to go"));
        URI next;
        try {
            next = current.resolve(location.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Unusable redirect from " + current + ": " + location, e);
        }
        policy.checkRedirect(next);
        return next;
    }

    private static byte[] bodyOf(URI uri, HttpResponse<byte[]> response) throws IOException {
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + uri);
        }
        return response.body();
    }

    /**
     * A list published in a directory, which is where a PKI with no route to the internet
     * puts it. The URL names the host, the entry and the attribute. Read with a maximum
     * message size, so the directory's answer is bounded the way an HTTP body is.
     */
    private byte[] readFromDirectory(String url, URI uri) throws IOException {
        LDAPURL ldapUrl;
        try {
            ldapUrl = new LDAPURL(url);
        } catch (LDAPException e) {
            throw new IOException("Not a usable LDAP URL: " + url, e);
        }
        LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis((int) Math.min(Integer.MAX_VALUE, connectTimeout.toMillis()));
        options.setResponseTimeoutMillis(Math.min(readTimeout.toMillis(), maxTime.toMillis()));
        // The encoding adds a little to the list itself; this is a bound, not an exact size.
        options.setMaxMessageSize((int) Math.min(Integer.MAX_VALUE, maxBytes + 64 * 1024));
        options.setFollowReferrals(false);
        boolean secure = "ldaps".equalsIgnoreCase(uri.getScheme());
        SocketFactory sockets = SocketFactory.getDefault();
        if (secure) {
            sockets = SSLSocketFactory.getDefault();
            options.setSSLSocketVerifier(new HostNameSSLSocketVerifier(true));
        }
        try (LDAPConnection connection =
                new LDAPConnection(sockets, options, ldapUrl.getHost(), ldapUrl.getPort())) {
            // Anonymous: a CRL is published to be read, and this application's directory
            // credentials are for its own directory, which this need not be.
            SearchResultEntry entry = connection.getEntry(
                    ldapUrl.getBaseDN().toString(), CRL_ATTRIBUTE + ";binary", CRL_ATTRIBUTE);
            if (entry == null) {
                throw new IOException("No entry at " + url);
            }
            for (Attribute attribute : entry.getAttributes()) {
                if (attribute.getBaseName().equalsIgnoreCase(CRL_ATTRIBUTE) && attribute.size() > 0) {
                    byte[] bytes = attribute.getValueByteArray();
                    if (bytes.length > maxBytes) {
                        throw new IOException("CRL at " + url + " is " + bytes.length + " bytes, over the limit");
                    }
                    return bytes;
                }
            }
            throw new IOException("No certificateRevocationList at " + url);
        } catch (LDAPException e) {
            throw new IOException("Could not read " + url + ": " + e.getExceptionMessage(), e);
        }
    }

    private static URI parse(String url) throws IOException {
        try {
            return URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Not a usable URL: " + url, e);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static Duration remaining(long deadline, Duration ceiling) {
        long left = Math.max(1, deadline - System.nanoTime());
        return Duration.ofNanos(Math.min(left, ceiling.toNanos()));
    }

    /** Collects a body up to a limit, and gives up the moment it passes it. */
    private static final class Limited implements HttpResponse.BodySubscriber<byte[]> {

        private final long limit;
        private final String refusal;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        Limited(long limit, String refusal) {
            this.limit = limit;
            this.refusal = refusal;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (refusal != null) {
                subscription.cancel();
                body.completeExceptionally(new IOException(refusal));
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if ((long) received.size() + buffer.remaining() > limit) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException(
                            "the response is over the limit of " + limit + " bytes; stopped reading it"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                received.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(received.toByteArray());
        }
    }
}
