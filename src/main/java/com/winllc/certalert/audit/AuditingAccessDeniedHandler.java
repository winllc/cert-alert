package com.winllc.certalert.audit;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Records a refusal by the security filter chain - a role it lacks, or a missing or wrong
 * CSRF token - as {@code access.denied}, then answers it however it was answered before.
 */
public class AuditingAccessDeniedHandler implements AccessDeniedHandler {

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
        delegate.handle(request, response, denied);
    }
}
