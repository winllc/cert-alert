package com.winllc.certalert.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.WebInvocationPrivilegeEvaluator;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

/**
 * Where a sign-in lands - by password, OpenID Connect or SAML: back on the page that sent
 * somebody to the sign-in page, as Spring's own handler does - unless the person who has
 * just signed in may not read it, in which case they start at {@code /} like anybody who
 * came to the sign-in page directly.
 *
 * <p>The page that was asked for need not be theirs. A session that ends on an
 * administration page leaves that page saved for whoever signs in next, and a demo invites
 * signing in as one account after another; following the saved page regardless greeted
 * them with a 403. Whether they may read it is asked of the filter chain's own rules, so
 * the answer is the one the page itself would give.
 *
 * <p>The privilege evaluator is looked up when somebody signs in, not when the chain is
 * built: it is made from the filter chains, this handler among them.
 *
 * <p>Both redirects, to the sign-in page and back to the saved page, are relative. Spring builds
 * them absolute from the host and scheme this server sees; behind a proxy that does not pass the
 * original ones on, that is the wrong origin, and the Content-Security-Policy's form-action
 * 'self' refuses the redirect after the sign-in form is posted - the Sign in button seems to do
 * nothing.
 */
public final class SignInSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final RequestCache requestCache = new HttpSessionRequestCache();
    private final ObjectProvider<WebInvocationPrivilegeEvaluator> privileges;

    public SignInSuccessHandler(ObjectProvider<WebInvocationPrivilegeEvaluator> privileges) {
        this.privileges = privileges;
    }

    /** Where somebody not signed in is sent: the sign-in page, by a relative redirect. */
    public static LoginUrlAuthenticationEntryPoint signInPage() {
        LoginUrlAuthenticationEntryPoint signIn = new LoginUrlAuthenticationEntryPoint("/login");
        signIn.setFavorRelativeUris(true);
        return signIn;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws ServletException, IOException {
        SavedRequest saved = requestCache.getRequest(request, response);
        if (saved instanceof DefaultSavedRequest page) {
            if (mayRead(page, authentication)) {
                // As the parent does, but by path: the saved URL names the origin as this server saw it.
                clearAuthenticationAttributes(request);
                getRedirectStrategy().sendRedirect(request, response, withinApplication(page));
                return;
            }
            requestCache.removeRequest(request, response);
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }

    /** The saved page's redirect URL without its origin and context path, which the redirect adds back. */
    private static String withinApplication(DefaultSavedRequest page) {
        URI url = URI.create(page.getRedirectUrl());
        String path = url.getRawPath().substring(page.getContextPath().length());
        return url.getRawQuery() == null ? path : path + "?" + url.getRawQuery();
    }

    private boolean mayRead(DefaultSavedRequest page, Authentication authentication) {
        WebInvocationPrivilegeEvaluator evaluator = privileges.getIfUnique();
        if (evaluator == null) {
            return true;
        }
        String contextPath = page.getContextPath();
        String path = page.getRequestURI().substring(contextPath.length());
        return evaluator.isAllowed(contextPath, path, page.getMethod(), authentication);
    }
}
