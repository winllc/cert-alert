package com.winllc.certalert.demo;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The demo's idea of security: sign in as anybody, change nothing.
 *
 * <p>Replaces the ordinary configuration rather than relaxing it, so the two policies are
 * never half-applied. Authentication is the ordinary one - a bind against the directory,
 * which here is the demo's own - because what this application shows somebody depends on
 * who they are, and a demo that signed everybody in as one administrator could only ever
 * demonstrate one answer. The sign-in page lists the accounts and what each one will see,
 * so being each of them in turn is the demonstration.
 *
 * <p><strong>Read-only is a rule about the request, not about the buttons.</strong> A
 * demo whose safety depended on the UI not offering something would be one page of
 * somebody's curiosity away from a directory being resynced. So anything that is not a
 * plain read is refused here, before any controller sees it, and the buttons that provoke
 * it stay exactly where they are - for every role, the administrator included.
 *
 * <p>That makes the write rules the ordinary configuration carries redundant here: an
 * endpoint only an administrator may post to is refused for them too. What is left to
 * decide is who may <em>read</em> what, which is a much shorter list, and signing in is
 * what decides it.
 *
 * <p>The exception is the three endpoints the search tables read through. They are POSTs
 * because DataTables sends its paging, ordering and filters as a body, and they write
 * nothing; without them the demo has no tables.
 */
@Configuration
@EnableWebSecurity
@Conditional(DemoMode.On.class)
public class DemoSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(DemoSecurityConfig.class);

    /** Served before anybody signs in - the sign-in page itself, and what it is made of. */
    private static final String[] PUBLIC_PATHS = {
        "/login", "/css/**", "/js/**", "/img/**", "/webjars/**", "/favicon.ico", "/error"
    };

    /** Reads that arrive as POSTs, because that is how DataTables asks. */
    private static final String[] READ_ONLY_POSTS = {
        "/api/v1/datatables/users", "/api/v1/datatables/servers", "/api/v1/datatables/audit"
    };

    /**
     * What a visitor is told when they press something.
     *
     * <p>Said in the response rather than only in the page's script, so that pressing a
     * button and calling the endpoint directly get the same answer. A bare 403 reads like
     * something is broken; this reads like the demo working as intended.
     */
    public static final String REFUSAL = "This is a read-only demo: nothing here can be changed.";

    /**
     * What somebody is told when the page itself is not theirs to read.
     *
     * <p>A different sentence from {@link #REFUSAL}, because it is a different refusal: not
     * "nothing can be changed" but "this role does not see this". Telling a reader that the
     * administration page is read-only would leave them wondering why they cannot read it.
     */
    public static final String DENIED =
            "This demo account does not have the role that page is for. Sign out and pick another.";

    @Bean
    public SecurityFilterChain demoFilterChain(
            HttpSecurity http, List<AuthenticationProvider> authenticationProviders, ObjectMapper json)
            throws Exception {
        log.warn("DEMO MODE: every account in this directory is published on the sign-in page with "
                + "its password. Nothing can be changed, but everything the directory holds can be "
                + "read by anyone who can reach this application.");

        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PUBLIC_PATHS)
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        // The reads that are an administrator's, and the only place the
                        // roles differ once every write is refused anyway.
                        .requestMatchers("/admin", "/api/v1/audit/summary", "/actuator/**")
                        .hasRole("ADMIN")
                        // The search tables, which ask by POST and write nothing.
                        .requestMatchers(HttpMethod.POST, READ_ONLY_POSTS)
                        .authenticated()
                        // Everything else: signed in, and only looking.
                        .anyRequest()
                        .access((authentication, context) -> new AuthorizationDecision(
                                signedIn(authentication.get()) && isRead(context.getRequest()))))
                .formLogin(form -> form.loginPage("/login").permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll())
                // A denial from the authorization filter never reaches the controller
                // advice - it is translated further down the chain - so the wording has to
                // be put on the response here.
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(refuse(json)));

        // The same providers the ordinary configuration uses, so a demo password is checked
        // by binding to the directory exactly as a real one is.
        authenticationProviders.forEach(http::authenticationProvider);

        return http.build();
    }

    /**
     * Answers a refusal with the reason - as problem detail to a script, as a page to a
     * browser.
     *
     * <p>Both, because there are now two ways to be refused. Pressing a button is a
     * script's fetch and wants the wording in a body it can show. Following a link to a
     * page this role may not read is a navigation, and answering that with JSON puts raw
     * problem detail in somebody's browser window - which is what happened when every read
     * was open to everybody and a refusal could only ever have been a fetch.
     */
    private static AccessDeniedHandler refuse(ObjectMapper json) {
        return (request, response, denied) -> {
            if (response.isCommitted()) {
                return;
            }
            if (wantsHtml(request)) {
                // Forwarded by hand rather than through AccessDeniedHandlerImpl, which
                // forwards without setting the error attributes the error controller reads
                // - so the page came back as a 500 describing itself as a server fault,
                // when what happened is that this role may not read that page.
                response.setStatus(HttpStatus.FORBIDDEN.value());
                request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, HttpStatus.FORBIDDEN.value());
                request.setAttribute(RequestDispatcher.ERROR_MESSAGE, DENIED);
                request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
                request.getRequestDispatcher("/error").forward(request, response);
                return;
            }
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, REFUSAL);
            problem.setTitle("Read-only demo");
            problem.setInstance(URI.create(request.getRequestURI()));

            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            json.writeValue(response.getWriter(), problem);
        };
    }

    /**
     * Whether this is somebody's browser rather than a script.
     *
     * <p>The same test the controller advice makes, for the same reason: one failure has
     * two audiences.
     */
    private static boolean wantsHtml(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/") || path.startsWith("/actuator/")) {
            return false;
        }
        String accept = request.getHeader("Accept");
        if (accept == null || accept.isBlank() || accept.contains("text/html")) {
            return true;
        }
        return !accept.contains("json") && !accept.contains("xml");
    }

    /**
     * Whether somebody actually signed in.
     *
     * <p>Not {@code isAuthenticated()}, which answers true for the anonymous token every
     * request carries when nobody has signed in - the trap that let a demo serve every page
     * to a visitor who had not been near the sign-in form.
     *
     * <p>Denying an anonymous request here is what sends a browser to the sign-in page:
     * Spring's exception translation turns a refusal into the entry point for somebody
     * anonymous, and into the read-only refusal below for somebody already signed in. Two
     * answers to the same status, and each is the right one for who asked.
     */
    private static boolean signedIn(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /**
     * Whether this request only looks.
     *
     * <p>By method rather than by path: a list of everything that writes is a list somebody
     * has to remember to add to, and the one that gets forgotten is the one that matters.
     * The safe methods are the ones HTTP already says are safe.
     */
    private static boolean isRead(HttpServletRequest request) {
        String method = request.getMethod();
        return HttpMethod.GET.matches(method)
                || HttpMethod.HEAD.matches(method)
                || HttpMethod.OPTIONS.matches(method);
    }

}
