package com.winllc.certalert.license;

import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseState;
import com.winllc.certalert.security.DirectoryPrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Applies the license to every request that reaches a controller:
 *
 * <ul>
 *   <li><b>Unlicensed</b>: a browser is sent to the license screen (an administrator) or to
 *       the page saying the application is not licensed (anyone else); a script gets 403
 *       {@code license_required}. Sign-in and sign-out, static assets, health, the error
 *       page and the license screen itself are excluded where this is registered.
 *   <li><b>Read-only</b>: GET, HEAD and OPTIONS work - and the search tables, which read by
 *       POST; anything else gets 403 {@code license_read_only}.
 *   <li><b>{@link RequiresFeature}</b>: 403 {@code feature_not_licensed} unless the license
 *       lists the feature (in read-only mode, reading what a listed feature gathered still
 *       works).
 * </ul>
 *
 * <p>Runs after the security filter chain, so somebody not signed in is asked to sign in
 * first rather than told about licensing. Refusals are thrown, for
 * {@code GlobalExceptionHandler} to answer like every other refusal.
 */
public class LicenseInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    /** POSTs that only read: DataTables sends its paging and filters as a body. */
    private static final List<PathPattern> READING_POSTS = List.of(
            PathPatternParser.defaultInstance.parse("/api/v1/datatables/**"));

    private final Licensing licensing;

    public LicenseInterceptor(Licensing licensing) {
        this.licensing = licensing;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod method)) {
            // Static resources and the like: nothing licensed about them.
            return true;
        }
        LicenseCheck check = licensing.current();

        if (check.mode() == LicenseState.Mode.UNLICENSED) {
            if (wantsHtml(request) && SAFE.contains(request.getMethod())) {
                String target = isAdmin()
                        ? LicensingConfiguration.LICENSE_SCREEN
                        : LicensingConfiguration.UNLICENSED_PAGE;
                response.sendRedirect(request.getContextPath() + target);
                return false;
            }
            throw LicenseRequiredException.unlicensed(check);
        }

        if (check.mode() == LicenseState.Mode.READ_ONLY
                && !SAFE.contains(request.getMethod())
                && !readingPost(request)) {
            throw LicenseRequiredException.readOnly(check);
        }

        RequiresFeature feature = method.getMethodAnnotation(RequiresFeature.class);
        if (feature == null) {
            feature = method.getBeanType().getAnnotation(RequiresFeature.class);
        }
        if (feature != null && !Licensing.shows(check, feature.value())) {
            throw LicenseRequiredException.feature(check, feature.value());
        }
        return true;
    }

    private static boolean readingPost(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        PathContainer path = PathContainer.parsePath(
                request.getRequestURI().substring(request.getContextPath().length()));
        return READING_POSTS.stream().anyMatch(pattern -> pattern.matches(path));
    }

    private static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(a -> DirectoryPrincipalResolver.ROLE_ADMIN.equals(a.getAuthority()));
    }

    /** The same test {@code GlobalExceptionHandler} makes: a page for a browser, JSON for a script. */
    static boolean wantsHtml(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/") || path.startsWith("/actuator/")) {
            return false;
        }
        String accept = request.getHeader("Accept");
        if (accept == null || accept.isBlank() || accept.contains("text/html")) {
            return true;
        }
        return !accept.contains("json") && !accept.contains("xml");
    }
}
