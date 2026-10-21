package com.winllc.certalert.webhook;

import com.winllc.certalert.api.ApiTokenAuthentication;
import com.winllc.certalert.audit.AuditContext;
import com.winllc.certalert.security.SignedInDirectoryUser;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook endpoints, managed by a signed-in administrator; auditors may read them and their
 * delivery logs. Never reachable with an API token (see {@code ApiEndpoints}).
 */
@RestController
public class WebhookAdminController {

    public static final String PATH = "/api/v1/admin/webhooks";

    private final Webhooks webhooks;
    private final WebhookPublisher publisher;

    public WebhookAdminController(Webhooks webhooks, WebhookPublisher publisher) {
        this.webhooks = webhooks;
        this.publisher = publisher;
    }

    /** An endpoint as the page lists it: the secret's first characters, never the secret. */
    public record EndpointRow(
            Long id,
            String name,
            String url,
            String format,
            Set<String> events,
            boolean enabled,
            String secretPrefix,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {

        static EndpointRow of(WebhookEndpoint endpoint) {
            return new EndpointRow(endpoint.getId(), endpoint.getName(), endpoint.getUrl(), endpoint.getFormat().name(),
                    endpoint.eventIds(), endpoint.isEnabled(), endpoint.getSecretPrefix(), endpoint.getCreatedBy(),
                    endpoint.getCreatedAt(), endpoint.getUpdatedAt());
        }
    }

    public record EndpointRequest(String name, String url, String format, List<String> events, Boolean enabled) {}

    /** The endpoint and its signing secret, shown this once. */
    public record WithSecret(EndpointRow endpoint, String secret) {}

    public record DeliveryRow(
            Long id,
            String eventId,
            String eventType,
            String state,
            int attempts,
            Instant createdAt,
            Instant nextAttemptAt,
            Instant deliveredAt,
            Integer lastStatus,
            String lastError) {

        static DeliveryRow of(WebhookDelivery delivery) {
            return new DeliveryRow(delivery.getId(), delivery.getEventId(), delivery.getEventType(), delivery.getState().name(),
                    delivery.getAttempts(), delivery.getCreatedAt(), delivery.getNextAttemptAt(), delivery.getDeliveredAt(),
                    delivery.getLastStatus(), delivery.getLastError());
        }
    }

    public record EventOption(String id) {}

    @GetMapping(PATH)
    public List<EndpointRow> list(Authentication authentication) {
        requireReader(authentication);
        return webhooks.list().stream().map(EndpointRow::of).toList();
    }

    @GetMapping(PATH + "/events")
    public List<EventOption> events() {
        return Arrays.stream(WebhookEvent.values())
                .filter(event -> event != WebhookEvent.TEST)
                .map(event -> new EventOption(event.id()))
                .toList();
    }

    @PostMapping(PATH)
    public ResponseEntity<WithSecret> create(@RequestBody EndpointRequest request, Authentication authentication) {
        requireAdministrator(authentication);
        Webhooks.WithSecret created = webhooks.create(request.name(), request.url(), format(request.format()),
                events(request.events()), AuditContext.actorOf(authentication).id());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new WithSecret(EndpointRow.of(created.endpoint()), created.secret()));
    }

    @PutMapping(PATH + "/{id}")
    public EndpointRow update(@PathVariable Long id, @RequestBody EndpointRequest request, Authentication authentication) {
        requireAdministrator(authentication);
        return EndpointRow.of(webhooks.update(id, request.name(), request.url(), format(request.format()),
                events(request.events()), request.enabled() == null || request.enabled()));
    }

    @PostMapping(PATH + "/{id}/secret")
    public ResponseEntity<WithSecret> rotate(@PathVariable Long id, Authentication authentication) {
        requireAdministrator(authentication);
        Webhooks.WithSecret rotated = webhooks.rotateSecret(id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new WithSecret(EndpointRow.of(rotated.endpoint()), rotated.secret()));
    }

    @PostMapping(PATH + "/{id}/test")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public DeliveryQueued test(@PathVariable Long id, Authentication authentication) {
        requireAdministrator(authentication);
        return new DeliveryQueued(publisher.test(webhooks.find(id), AuditContext.actorOf(authentication).id()));
    }

    public record DeliveryQueued(Long deliveryId) {}

    @DeleteMapping(PATH + "/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication authentication) {
        requireAdministrator(authentication);
        webhooks.delete(id);
    }

    @GetMapping(PATH + "/{id}/deliveries")
    public List<DeliveryRow> deliveries(
            @PathVariable Long id, @RequestParam(defaultValue = "50") int limit, Authentication authentication) {
        requireReader(authentication);
        return webhooks.deliveries(id, limit).stream().map(DeliveryRow::of).toList();
    }

    private static WebhookEndpoint.Format format(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return WebhookEndpoint.Format.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("A webhook's format is JSON, SLACK or TEAMS");
        }
    }

    private static List<WebhookEvent> events(List<String> ids) {
        return ids == null ? List.of() : ids.stream().map(WebhookEvent::of).toList();
    }

    private static void requireAdministrator(Authentication authentication) {
        if (authentication instanceof ApiTokenAuthentication || !SignedInDirectoryUser.isAdmin(authentication)) {
            throw new AccessDeniedException("Webhooks are an administrator's to manage");
        }
    }

    private static void requireReader(Authentication authentication) {
        if (authentication instanceof ApiTokenAuthentication
                || (!SignedInDirectoryUser.isAdmin(authentication) && !SignedInDirectoryUser.isAuditor(authentication))) {
            throw new AccessDeniedException("Webhooks are an administrator's or an auditor's to read");
        }
    }
}
