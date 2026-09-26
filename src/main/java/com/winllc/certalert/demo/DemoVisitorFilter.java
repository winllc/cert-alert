package com.winllc.certalert.demo;

import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.DirectoryPrincipalResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Signs every visitor in as the same person, without asking them anything.
 *
 * <p>Deliberately a real authentication rather than Spring's anonymous one, which looks
 * equivalent and is not. A servlet's {@code getUserPrincipal()} returns null for an
 * anonymous token by design, and this application's controllers take {@code Authentication}
 * as a method parameter - which Spring MVC resolves from exactly that. Under anonymous
 * authentication every one of those parameters arrives null, and every check written as
 * "is this an administrator" answers no: the navigation shows the administration pages,
 * because that check reads the context directly, and the pages themselves refuse to load.
 *
 * <p>So the demo visitor is authenticated in the ordinary way, holding the ordinary roles.
 * What they may do is decided by the request being a read, not by who they are.
 */
public class DemoVisitorFilter extends OncePerRequestFilter {

    private final Authentication visitor;

    public DemoVisitorFilter(DemoProperties demo) {
        List<SimpleGrantedAuthority> roles =
                List.of(
                        new SimpleGrantedAuthority(DirectoryPrincipalResolver.ROLE_USER),
                        new SimpleGrantedAuthority(DirectoryPrincipalResolver.ROLE_ADMIN));
        DirectoryPrincipal principal = new DirectoryPrincipal(
                demo.getSignedInAs().isBlank() ? "demo" : demo.getSignedInAs(),
                demo.getVisitor(),
                null,
                null,
                DirectoryPrincipal.AuthenticationMethod.NONE,
                roles);
        this.visitor = UsernamePasswordAuthenticationToken.authenticated(principal, null, roles);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(visitor);
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            // The container reuses threads, and a context left behind is the next request's.
            SecurityContextHolder.clearContext();
        }
    }
}
