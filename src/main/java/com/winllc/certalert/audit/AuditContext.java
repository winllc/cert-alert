package com.winllc.certalert.audit;

import com.winllc.certalert.security.DirectoryPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who is acting and from where, read from the current request and security context.
 *
 * <p>The client address is the request's remote address as the servlet container reports
 * it - which is where the application's own proxy handling ({@code server.forward-headers-strategy})
 * already applies. A client-supplied {@code X-Forwarded-For} is never read here.
 */
public final class AuditContext {

    /** The request attribute holding this request's id, so every event it causes shares one. */
    public static final String REQUEST_ID = AuditContext.class.getName() + ".requestId";

    private static final int MAX_USER_AGENT = 512;

    private AuditContext() {}

    /** The current request, if this thread is serving one. */
    public static HttpServletRequest request() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    /** Fills in whatever the record leaves unset from the request and the signed-in user. */
    static void capture(AuditRecord record) {
        if (record.contextCaptured()) {
            return;
        }
        record.markContextCaptured();
        HttpServletRequest request = request();
        if (!record.hasActor()) {
            record.actor(currentActor());
        }
        if (request != null) {
            if (record.source() == null) {
                record.source(sourceOf(request));
            }
            record.requestId(requestId(request));
            record.session(sessionOf(request));
        }
    }

    /** The signed-in person or service, or the system where nobody is. */
    public static AuditRecord.Actor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return actorOf(authentication);
    }

    public static AuditRecord.Actor actorOf(Authentication authentication) {
        if (authentication == null
                || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return AuditRecord.Actor.system(null);
        }
        List<String> roles = rolesOf(authentication);
        if (authentication instanceof AuditTokenAuthentication token) {
            return new AuditRecord.Actor(AuditRecord.ActorType.SERVICE, token.getName(), token.tokenName(), roles);
        }
        if (authentication.getPrincipal() instanceof DirectoryPrincipal principal) {
            return new AuditRecord.Actor(
                    AuditRecord.ActorType.USER, principal.getUsername(), principal.getDisplayName(), roles);
        }
        return new AuditRecord.Actor(AuditRecord.ActorType.USER, authentication.getName(), null, roles);
    }

    public static List<String> rolesOf(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .distinct()
                .sorted()
                .toList();
    }

    public static AuditRecord.Source sourceOf(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent != null && agent.length() > MAX_USER_AGENT) {
            agent = agent.substring(0, MAX_USER_AGENT);
        }
        return new AuditRecord.Source(request.getRemoteAddr(), agent);
    }

    static String requestId(HttpServletRequest request) {
        Object id = request.getAttribute(REQUEST_ID);
        if (id == null) {
            id = UUID.randomUUID().toString();
            request.setAttribute(REQUEST_ID, id);
        }
        return id.toString();
    }

    /** The first 16 hex characters of the SHA-256 of the session id; never the id itself. */
    static String sessionOf(HttpServletRequest request) {
        try {
            HttpSession session = request.getSession(false);
            return session == null ? null : AuditFormat.sha256Hex(session.getId()).substring(0, 16);
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
