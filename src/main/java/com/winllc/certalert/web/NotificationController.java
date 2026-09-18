package com.winllc.certalert.web;

import com.winllc.certalert.config.NotificationProperties;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.DirectoryPrincipalResolver;
import com.winllc.certalert.service.AuditActors;
import com.winllc.certalert.service.NotificationService;
import com.winllc.certalert.service.NotificationSettingsService;
import com.winllc.certalert.web.dto.NotificationRow;
import com.winllc.certalert.web.dto.NotificationSettingsRequest;
import com.winllc.certalert.web.dto.NotificationSettingsView;
import com.winllc.certalert.web.dto.PageResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Somebody's own notifications.
 *
 * <p>Everything here is scoped to whoever is asking, taken from the security context rather
 * than a path variable: there is no reading somebody else's, so there is no way to ask.
 *
 * <p>Somebody signed in who the directory does not know - an administrator named in the
 * configuration but holding no entry - has none, because a notification is addressed to a
 * directory entry. They see an empty list rather than an error.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationService notificationService;
    private final NotificationSettingsService settingsService;
    private final NotificationProperties properties;
    private final DirectoryUserRepository userRepository;

    public NotificationController(
            NotificationService notificationService,
            NotificationSettingsService settingsService,
            NotificationProperties properties,
            DirectoryUserRepository userRepository) {
        this.notificationService = notificationService;
        this.settingsService = settingsService;
        this.properties = properties;
        this.userRepository = userRepository;
    }

    @GetMapping
    public PageResponse<NotificationRow> mine(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        Long userId = directoryUserId(authentication);
        if (userId == null) {
            return new PageResponse<>(List.of(), 0, size, 0, 0, true, true);
        }
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.of(
                notificationService.forRecipient(userId, PageRequest.of(Math.max(page, 0), bounded)),
                NotificationRow::from);
    }

    /** What the bell in the navigation bar counts. */
    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(Authentication authentication) {
        Long userId = directoryUserId(authentication);
        return Map.of("count", userId == null ? 0L : notificationService.unreadCount(userId));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable Long id, Authentication authentication) {
        Long userId = directoryUserId(authentication);
        if (userId != null) {
            notificationService.markRead(userId, id);
        }
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead(Authentication authentication) {
        Long userId = directoryUserId(authentication);
        return Map.of("read", userId == null ? 0 : notificationService.markAllRead(userId));
    }

    /**
     * How far ahead the round-up looks. Anybody signed in may read it - it is what the page
     * says the email will tell them about - and administrators may change it.
     */
    @GetMapping("/settings")
    public NotificationSettingsView settings(Authentication authentication) {
        return NotificationSettingsView.of(
                settingsService.current(), properties.getEmail().isEnabled(), isAdmin(authentication));
    }

    /** Administrators only; see SecurityConfig. */
    @PutMapping("/settings")
    public NotificationSettingsView updateSettings(
            @RequestBody NotificationSettingsRequest request, Authentication authentication) {

        return NotificationSettingsView.of(
                settingsService.update(request.leadDays(), AuditActors.current(AuditActors.SYSTEM)),
                properties.getEmail().isEnabled(),
                isAdmin(authentication));
    }

    /** Runs the round-up now rather than waiting for the schedule. Administrators only. */
    @PostMapping("/digest")
    public NotificationService.DigestResult digest() {
        return notificationService.digest();
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> DirectoryPrincipalResolver.ROLE_ADMIN.equals(authority.getAuthority()));
    }

    /**
     * Which directory entry is asking.
     *
     * <p>Resolved when they signed in, where it could be: on a deployment whose first sweep
     * has not finished, or one that swept after somebody signed in, there was no entry to
     * resolve them to and the session carries none. Rather than showing that person an
     * empty page until they sign out and back in, their name is looked up now - one indexed
     * query, on a page they asked for.
     */
    private Long directoryUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof DirectoryPrincipal principal)) {
            return null;
        }
        if (principal.getDirectoryUserId() != null) {
            return principal.getDirectoryUserId();
        }
        List<DirectoryUser> named = userRepository.findByIdentifier(principal.getUsername().toLowerCase(Locale.ROOT));
        // Two people answering to one name is no basis for showing either of them the
        // other's notifications.
        return named.size() == 1 ? named.getFirst().getId() : null;
    }
}
