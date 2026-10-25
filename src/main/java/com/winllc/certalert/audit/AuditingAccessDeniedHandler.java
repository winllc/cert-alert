package com.winllc.certalert.audit;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Records a refusal by the security filter chain - a role it lacks, or a missing or wrong
 * CSRF token - as {@code access.denied}, then answers it however it was answered before.
 *
 * <p>Except the sign-in and sign-out forms. Posted with the token of a session that has since
 * ended - it timed out, the demo was restarted, a second click came after the first signed in -
 * they fail the CSRF check, and a 403 there reads as the sign-in page itself being broken.
 * They are refused and recorded all the same, but answered with the sign-in page ("Your
 * session has ended") or, for somebody signed in, the start page.
 */
public class AuditingAccessDeniedHandler implements AccessDeniedHandler {

    /** The forms a stale token is answered on with a redirect rather than a 403. */
    private static final Set<String> SIGN_IN_FORMS = Set.of("/login", "/logout");

    private final AccessDeniedHandler delegate;
    private final AccessDenials denials;

    public AuditingAccessDeniedHandler(AccessDeniedHandler delegate, AccessDenials denials) {
        this.delegate = delegate;
        this.denials = denials;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException, ServletException {
        try {
            denials.denied(request, denied instanceof CsrfException ? "csrf" : "role_required", denied.getMessage());
        } catch (RuntimeException ignored) {
            // A refusal is answered whether or not it could be noted.
        }
        if (denied instanceof CsrfException && "POST".equals(request.getMethod()) && !response.isCommitted()
                && SIGN_IN_FORMS.contains(request.getRequestURI().substring(request.getContextPath().length()))) {
            response.sendRedirect(request.getContextPath() + (signedIn() ? "/" : "/login?expired"));
            return;
        }
        delegate.handle(request, response, denied);
    }

    private static boolean signedIn() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
