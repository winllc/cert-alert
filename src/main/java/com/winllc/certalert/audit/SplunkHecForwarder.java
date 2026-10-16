package com.winllc.certalert.audit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Splunk's HTTP Event Collector: {@code POST /services/collector/event} with
 * {@code Authorization: Splunk <token>}, up to 100 events a request, each as
 * {@code {"time":<epoch seconds.ms>,"host":...,"source":"certalert","sourcetype":"ens:audit","index"?,"event":<the event>}}.
 * The event is the stored line itself, so Splunk sees exactly what the chain holds and can
 * de-duplicate on its {@code id}. TLS is checked against the JVM's trust store unless
 * {@code ENS_AUDIT_SPLUNK_INSECURE} says otherwise.
 */
public class SplunkHecForwarder implements AuditForwarder {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final URI endpoint;
    private final String token;
    private final String host;
    private final String source;
    private final String sourcetype;
    private final String index;
    private final boolean insecure;

    public SplunkHecForwarder(EnsAuditProperties.Splunk settings, String host, String source) {
        String base = settings.getUrl().replaceAll("/+$", "");
        this.endpoint = URI.create(base.endsWith("/services/collector/event") ? base : base + "/services/collector/event");
        this.token = tokenOf(settings);
        this.host = host;
        this.source = source;
        this.sourcetype = settings.getSourcetype();
        this.index = settings.getIndex();
        this.insecure = settings.isInsecure();
    }

    static String tokenOf(EnsAuditProperties.Splunk settings) {
        if (!settings.getToken().isBlank()) {
            return settings.getToken();
        }
        if (!settings.getTokenFile().isBlank()) {
            try {
                return Files.readString(Path.of(settings.getTokenFile()), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read ENS_AUDIT_SPLUNK_TOKEN_FILE " + settings.getTokenFile(), e);
            }
        }
        return "";
    }

    @Override
    public String name() {
        return "splunk";
    }

    @Override
    public String describe() {
        return endpoint + " (sourcetype " + sourcetype + (index.isBlank() ? "" : ", index " + index)
                + (insecure ? ", TLS NOT VERIFIED" : "") + (token.isBlank() ? ", NO TOKEN" : "") + ")";
    }

    /** The request body: one HEC event object per stored event, back to back. */
    String payload(List<StoredEvent> events) {
        StringBuilder body = new StringBuilder();
        for (StoredEvent event : events) {
            long millis = event.time().toEpochMilli();
            body.append("{\"time\":").append(Math.floorDiv(millis, 1000)).append('.')
                    .append(String.format("%03d", Math.floorMod(millis, 1000)))
                    .append(",\"host\":").append(AuditFormat.string(host))
                    .append(",\"source\":").append(AuditFormat.string(source))
                    .append(",\"sourcetype\":").append(AuditFormat.string(sourcetype));
            if (!index.isBlank()) {
                body.append(",\"index\":").append(AuditFormat.string(index));
            }
            body.append(",\"event\":").append(event.line()).append('}');
        }
        return body.toString();
    }

    @Override
    public void send(List<StoredEvent> events) throws IOException {
        if (events.isEmpty()) {
            return;
        }
        byte[] body = payload(events).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
        try {
            if (insecure && connection instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(trustingEverything().getSocketFactory());
                https.setHostnameVerifier((name, session) -> true);
            }
            connection.setConnectTimeout((int) TIMEOUT.toMillis());
            connection.setReadTimeout((int) TIMEOUT.toMillis());
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Splunk " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String detail = read(connection.getErrorStream());
                throw new IOException("Splunk HEC answered " + status + (detail.isBlank() ? "" : ": " + detail));
            }
            read(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (in) {
            byte[] bytes = in.readNBytes(2000);
            return new String(bytes, StandardCharsets.UTF_8).trim();
        }
    }

    private static SSLContext trustingEverything() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
