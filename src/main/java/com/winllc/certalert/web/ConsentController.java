package com.winllc.certalert.web;

import com.winllc.certalert.audit.AuditLog;
import com.winllc.certalert.audit.AuditRecord;
import com.winllc.certalert.security.ConsentBanner;
import com.winllc.certalert.security.ConsentFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * The notice-and-consent banner and its "I agree" (DISA STIG). Reachable without signing in,
 * since agreeing comes first; see {@link ConsentFilter} for what it holds back until then.
 */
@Controller
public class ConsentController {

    /** Acknowledgments one client address may make in {@link #WINDOW}; beyond that, 429. */
    public static final int PER_ADDRESS = 10;

    static final Duration WINDOW = Duration.ofMinutes(1);

    private final ConsentBanner banner;
    private final AuditLog audit;
    private final Clock clock;

    private record Window(Instant start, int count) {}

    private final Map<String, Window> recent = new ConcurrentHashMap<>();

    public ConsentController(ConsentBanner banner, AuditLog audit, Clock clock) {
        this.banner = banner;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping(ConsentFilter.PATH)
    public String banner(Model model) {
        if (!banner.isEnabled()) {
            return "redirect:/";
        }
        model.addAttribute("banner", banner);
        return "consent";
    }

    /**
     * Records the agreement in the session and in the audit trail, then carries on to the
     * application - which, for somebody presenting a client certificate, signs them in.
     *
     * <p>Recorded before the session is marked, and in the audit log's strict mode: an
     * acknowledgment that cannot be audited is not one. Anybody may post here, so a session
     * that has already agreed is not recorded again, and one client address may agree only
     * {@link #PER_ADDRESS} times a minute: beyond that it is refused, not left unrecorded.
     */
    @PostMapping(ConsentFilter.PATH)
    public String acknowledge(HttpServletRequest request) {
        if (!banner.isEnabled() || ConsentFilter.acknowledged(request)) {
            return "redirect:/";
        }
        if (!withinLimit(request.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many acknowledgments from this address");
        }
        audit.record(AuditRecord.of(AuditRecord.Category.AUTHENTICATION, "auth.consent-acknowledged")
                .actor(AuditRecord.Actor.anonymous(null)));
        request.getSession(true).setAttribute(ConsentBanner.ACKNOWLEDGED, Boolean.TRUE);
        return "redirect:/";
    }

    private boolean withinLimit(String address) {
        Instant now = Instant.now(clock);
        if (recent.size() > 10_000) {
            recent.clear();
        }
        Window window = recent.compute(address == null ? "" : address, (key, held) ->
                held == null || !now.isBefore(held.start().plus(WINDOW))
                        ? new Window(now, 1)
                        : new Window(held.start(), held.count() + 1));
        return window.count() <= PER_ADDRESS;
    }
}
