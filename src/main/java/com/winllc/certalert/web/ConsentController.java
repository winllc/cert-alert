package com.winllc.certalert.web;

import com.winllc.certalert.audit.AuditLog;
import com.winllc.certalert.audit.AuditRecord;
import com.winllc.certalert.security.ConsentBanner;
import com.winllc.certalert.security.ConsentFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * The notice-and-consent banner and its "I agree" (DISA STIG). Reachable without signing in,
 * since agreeing comes first; see {@link ConsentFilter} for what it holds back until then.
 */
@Controller
public class ConsentController {

    private final ConsentBanner banner;
    private final AuditLog audit;

    public ConsentController(ConsentBanner banner, AuditLog audit) {
        this.banner = banner;
        this.audit = audit;
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
     * acknowledgment that cannot be audited is not one.
     */
    @PostMapping(ConsentFilter.PATH)
    public String acknowledge(HttpServletRequest request) {
        if (!banner.isEnabled()) {
            return "redirect:/";
        }
        audit.record(AuditRecord.of(AuditRecord.Category.AUTHENTICATION, "auth.consent-acknowledged")
                .actor(AuditRecord.Actor.anonymous(null)));
        request.getSession(true).setAttribute(ConsentBanner.ACKNOWLEDGED, Boolean.TRUE);
        return "redirect:/";
    }
}
