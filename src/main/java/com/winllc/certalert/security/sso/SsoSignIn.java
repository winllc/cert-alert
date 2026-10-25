package com.winllc.certalert.security.sso;

import com.winllc.certalert.security.ConsentBanner;
import com.winllc.certalert.security.DirectoryPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.core.Saml2ErrorCodes;
import org.springframework.security.saml2.core.Saml2ResponseValidatorResult;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.function.SingletonSupplier;

/**
 * Single sign-on, added to the sign-in chain where it is configured (see {@link SsoProperties}).
 *
 * <p>Nothing here runs unless {@code cert-alert.sso.oidc.enabled} or
 * {@code cert-alert.sso.saml.enabled} is set: no endpoints, no buttons, no providers. The
 * other ways in are unchanged either way.
 *
 * <p><b>OpenID Connect.</b> The authorization code flow, always with PKCE, from the
 * provider's discovery document (read when first needed, so a provider that is down does not
 * stop the application starting). The ID token's signature (by the provider's published
 * keys, with the configured algorithm), issuer, audience, authorized party, expiry and nonce
 * are checked, with {@code clock-skew} allowed. No tokens are kept once the person is
 * matched.
 *
 * <p><b>SAML 2.0.</b> The response or its assertion must be signed by a key in the identity
 * provider's metadata; its issuer must be that provider; it must answer a request this
 * application made and has not seen answered (InResponseTo, see
 * {@link JdbcSaml2AuthenticationRequests}), for this service provider (audience and
 * destination), and be in date within {@code clock-skew}. Unsolicited responses are refused.
 *
 * <p>Either way the person is matched to a directory entry by {@link SsoIdentityResolver},
 * and refused when there is none. Sign-ins and refusals are recorded as {@code auth.login}
 * with method {@code oidc} or {@code saml}, like any other.
 */
@Component
public class SsoSignIn {

    private static final Logger log = LoggerFactory.getLogger(SsoSignIn.class);

    /** Where a refused single sign-on is sent, so the page can say which kind of refusal. */
    public static final String FAILURE_URL = "/login?sso_error";

    private final SsoProperties properties;
    private final SsoIdentityResolver identities;
    public SsoSignIn(SsoProperties properties, SsoIdentityResolver identities) {
        this.properties = properties;
        this.identities = identities;
    }

    /**
     * Where SAML authentication requests wait for their answer: a bean, because that is
     * where Spring Security's SAML support looks for one - for sending them and for reading
     * the answer alike.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "cert-alert.sso.saml", name = "enabled", havingValue = "true")
    static class SamlRequests {

        @org.springframework.context.annotation.Bean
        JdbcSaml2AuthenticationRequests samlAuthenticationRequests(DataSource dataSource, Clock clock) {
            return new JdbcSaml2AuthenticationRequests(dataSource, clock);
        }
    }

    /** A button on the sign-in page. */
    public record Option(String label, String url) {}

    /** The buttons the sign-in page shows: one per provider configured. */
    public List<Option> options() {
        List<Option> options = new ArrayList<>();
        if (properties.getOidc().isEnabled()) {
            options.add(new Option(properties.getOidc().getDisplayName(),
                    "/oauth2/authorization/" + SsoProperties.Oidc.REGISTRATION_ID));
        }
        if (properties.getSaml().isEnabled()) {
            options.add(new Option(properties.getSaml().getDisplayName(),
                    "/saml2/authenticate/" + SsoProperties.Saml.REGISTRATION_ID));
        }
        return options;
    }

    /** The paths single sign-on needs open before anybody has signed in. */
    public static final String[] PUBLIC_PATHS = {
        "/oauth2/authorization/**", "/login/oauth2/code/**", "/saml2/authenticate/**", "/login/saml2/sso/**",
        "/saml2/service-provider-metadata/**"
    };

    /**
     * Adds whichever providers are configured to the chain.
     *
     * @param success where a sign-in lands: the same handler as the password sign-in's, so
     *     nobody is returned to a saved page they may not read whichever way they signed in
     */
    public void apply(HttpSecurity http, AuthenticationEventPublisher events, AuthenticationSuccessHandler success)
            throws Exception {
        if (properties.getOidc().isEnabled() || properties.getSaml().isEnabled()) {
            // At startup, not at the first sign-in: a deployment without its public address
            // configured does not start with single sign-on half working.
            String publicUrl = properties.requirePublicUrl();
            if (properties.getSaml().isEnabled()) {
                entityIdOf(properties.getSaml(), publicUrl);
            }
        }
        if (properties.getOidc().isEnabled()) {
            applyOidc(http, success);
            log.info("Single sign-on: OpenID Connect from {}", properties.getOidc().getIssuerUri());
        }
        if (properties.getSaml().isEnabled()) {
            applySaml(http, events, success);
            log.info("Single sign-on: SAML 2.0 from {}", properties.getSaml().getMetadataLocation());
        }
    }

    // --- OpenID Connect ------------------------------------------------------------------

    private void applyOidc(HttpSecurity http, AuthenticationSuccessHandler success) throws Exception {
        SsoProperties.Oidc oidc = properties.getOidc();
        require(oidc.getIssuerUri(), "cert-alert.sso.oidc.issuer-uri");
        require(oidc.getClientId(), "cert-alert.sso.oidc.client-id");
        ClientRegistrationRepository registrations = lazyRegistrations(oidc);

        OidcIdTokenDecoderFactory decoders = new OidcIdTokenDecoderFactory();
        decoders.setJwsAlgorithmResolver(registration -> SignatureAlgorithm.from(oidc.getJwsAlgorithm()));
        decoders.setJwtValidatorFactory(registration -> {
            OidcIdTokenValidator idToken = new OidcIdTokenValidator(registration);
            idToken.setClockSkew(oidc.getClockSkew());
            return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(oidc.getClockSkew()), idToken);
        });

        http.oauth2Login(login -> login
                .loginPage("/login")
                .failureUrl(FAILURE_URL)
                .successHandler(success)
                .clientRegistrationRepository(registrations)
                .authorizedClientRepository(new NothingKept())
                .tokenEndpoint(token -> { })
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUsers(oidc)))
                .withObjectPostProcessor(new org.springframework.security.config.ObjectPostProcessor<Object>() {
                    @Override
                    public <O> O postProcess(O object) {
                        if (object instanceof org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider provider) {
                            provider.setJwtDecoderFactory(decoders);
                        }
                        return object;
                    }
                }));
    }

    /** The registration, from the provider's discovery document, read the first time it is wanted. */
    private ClientRegistrationRepository lazyRegistrations(SsoProperties.Oidc oidc) {
        String publicUrl = properties.requirePublicUrl();
        Supplier<ClientRegistration> registration = SingletonSupplier.of(() -> {
            ClientRegistration.Builder builder = ClientRegistrations.fromOidcIssuerLocation(oidc.getIssuerUri())
                    .registrationId(SsoProperties.Oidc.REGISTRATION_ID)
                    .clientId(oidc.getClientId())
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    // Pinned, not built from the request's Host or Forwarded headers.
                    .redirectUri(publicUrl + "/login/oauth2/code/" + SsoProperties.Oidc.REGISTRATION_ID)
                    .scope(oidc.getScopes())
                    // Always present; the claim naming the person is read separately, so a
                    // token without it is refused as a sign-in rather than failing as an error.
                    .userNameAttributeName("sub")
                    // PKCE whether or not there is a client secret.
                    .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build());
            if (StringUtils.hasText(oidc.getClientSecret())) {
                builder.clientSecret(oidc.getClientSecret())
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
            } else {
                builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
            }
            return builder.build();
        });
        return registrationId -> {
            if (!SsoProperties.Oidc.REGISTRATION_ID.equals(registrationId)) {
                return null;
            }
            try {
                return registration.get();
            } catch (RuntimeException e) {
                log.error("Could not read the OpenID Connect provider's configuration from {}: {}",
                        oidc.getIssuerUri(), e.toString());
                return null;
            }
        };
    }

    private OAuth2UserService<OidcUserRequest, OidcUser> oidcUsers(SsoProperties.Oidc oidc) {
        OidcUserService provider = new OidcUserService();
        return request -> {
            OidcUser user = provider.loadUser(request);
            Map<String, Object> claims = user.getClaims();
            Object name = claims.get(oidc.getUsernameClaim());
            Collection<String> groups = listOf(StringUtils.hasText(oidc.getGroupsClaim()) ? claims.get(oidc.getGroupsClaim()) : null);
            DirectoryPrincipal person = identities
                    .resolve(name == null ? null : name.toString(), oidc.getMatch(), groups, oidc,
                            DirectoryPrincipal.AuthenticationMethod.OIDC)
                    .orElseThrow(() -> {
                        String message = "The identity provider signed in " + describe(oidc.getUsernameClaim(), name)
                                + ", whom the directory does not hold";
                        return new OAuth2AuthenticationException(
                                new OAuth2Error("directory_entry_not_found", message, null), message);
                    });
            return new OidcDirectoryPrincipal(person, user.getIdToken(), user.getUserInfo());
        };
    }

    /** No access or refresh token is kept: the sign-in is all this application wants of the provider. */
    private static final class NothingKept implements OAuth2AuthorizedClientRepository {
        @Override
        @SuppressWarnings("unchecked")
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                String clientRegistrationId, Authentication principal, HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(
                OAuth2AuthorizedClient authorizedClient,
                Authentication principal,
                HttpServletRequest request,
                HttpServletResponse response) {
            // kept nowhere
        }

        @Override
        public void removeAuthorizedClient(
                String clientRegistrationId, Authentication principal, HttpServletRequest request,
                HttpServletResponse response) {
            // nothing to remove
        }
    }

    // --- SAML 2.0 ------------------------------------------------------------------------

    private void applySaml(HttpSecurity http, AuthenticationEventPublisher events, AuthenticationSuccessHandler success)
            throws Exception {
        SsoProperties.Saml saml = properties.getSaml();
        require(saml.getMetadataLocation(), "cert-alert.sso.saml.metadata-location");
        RelyingPartyRegistrationRepository registrations = lazyRelyingParty(saml);

        OpenSaml5AuthenticationProvider provider = new OpenSaml5AuthenticationProvider();
        provider.setAssertionValidator(
                OpenSaml5AuthenticationProvider.AssertionValidator.builder().clockSkew(saml.getClockSkew()).build());
        // The defaults (status, destination, issuer, InResponseTo where present), and an
        // answer to a request of this application's is required, not just allowed.
        provider.setResponseValidator(OpenSaml5AuthenticationProvider.ResponseValidator.withDefaults(token ->
                StringUtils.hasText(token.getResponse().getInResponseTo())
                        ? Saml2ResponseValidatorResult.success()
                        : Saml2ResponseValidatorResult.failure(new Saml2Error(Saml2ErrorCodes.INVALID_IN_RESPONSE_TO,
                                "The response answers no request of this application's (unsolicited responses are refused)"))));
        var standard = OpenSaml5AuthenticationProvider.createDefaultResponseAuthenticationConverter();
        provider.setResponseAuthenticationConverter(token -> toPerson(standard.convert(token), saml));

        // Its own manager, holding nothing else: a refusal here is final, not a cue to try
        // another way of signing the same response in.
        ProviderManager manager = new ProviderManager(provider);
        manager.setAuthenticationEventPublisher(events);

        http.saml2Login(login -> login
                .loginPage("/login")
                .failureUrl(FAILURE_URL)
                .relyingPartyRegistrationRepository(registrations)
                .authenticationManager(manager)
                .successHandler((request, response, authentication) -> {
                    carryConsent(request);
                    success.onAuthenticationSuccess(request, response, authentication);
                }));
        http.saml2Metadata(metadata -> metadata.metadataUrl("/saml2/service-provider-metadata/{registrationId}"));
    }

    /**
     * The browser agreed to the banner before it was sent to the identity provider; the
     * session the answer has just created should say so (see
     * {@link JdbcSaml2AuthenticationRequests}).
     */
    private static void carryConsent(HttpServletRequest request) {
        if (Boolean.TRUE.equals(request.getAttribute(JdbcSaml2AuthenticationRequests.CONSENTED))) {
            request.getSession(true).setAttribute(ConsentBanner.ACKNOWLEDGED, Boolean.TRUE);
        }
    }

    private Saml2Authentication toPerson(Saml2Authentication standard, SsoProperties.Saml saml) {
        Saml2AuthenticatedPrincipal asserted = (Saml2AuthenticatedPrincipal) standard.getPrincipal();
        Object name = StringUtils.hasText(saml.getUsernameAttribute())
                ? asserted.getFirstAttribute(saml.getUsernameAttribute())
                : asserted.getName();
        Collection<String> groups = StringUtils.hasText(saml.getGroupsAttribute())
                ? listOf(asserted.getAttribute(saml.getGroupsAttribute()))
                : List.of();
        DirectoryPrincipal person = identities
                .resolve(name == null ? null : name.toString(), saml.getMatch(), groups, saml,
                        DirectoryPrincipal.AuthenticationMethod.SAML)
                .orElseThrow(() -> new Saml2AuthenticationException(new Saml2Error(Saml2ErrorCodes.USERNAME_NOT_FOUND,
                        "The identity provider signed in "
                                + describe(StringUtils.hasText(saml.getUsernameAttribute()) ? saml.getUsernameAttribute() : "NameID", name)
                                + ", whom the directory does not hold")));
        SamlDirectoryPrincipal principal = new SamlDirectoryPrincipal(person, asserted.getName());
        return new Saml2Authentication(principal, standard.getSaml2Response(), principal.getAuthorities());
    }

    /** The relying party, from the identity provider's metadata, read the first time it is wanted. */
    private RelyingPartyRegistrationRepository lazyRelyingParty(SsoProperties.Saml saml) {
        String publicUrl = properties.requirePublicUrl();
        String entityId = entityIdOf(saml, publicUrl);
        Supplier<RelyingPartyRegistration> registration = SingletonSupplier.of(() -> {
            RelyingPartyRegistration.Builder builder = RelyingPartyRegistrations
                    .fromMetadataLocation(saml.getMetadataLocation())
                    .registrationId(SsoProperties.Saml.REGISTRATION_ID)
                    // Both pinned: the audience and destination a response must carry are this
                    // deployment's, whatever Host or Forwarded header the request came with.
                    .entityId(entityId)
                    .assertionConsumerServiceLocation(publicUrl + "/login/saml2/sso/" + SsoProperties.Saml.REGISTRATION_ID);
            if (StringUtils.hasText(saml.getSigningKeyLocation())) {
                Saml2X509Credential signing = signingCredential(saml);
                builder.signingX509Credentials(credentials -> credentials.add(signing));
            }
            return builder.build();
        });
        return registrationId -> {
            if (!SsoProperties.Saml.REGISTRATION_ID.equals(registrationId)) {
                return null;
            }
            try {
                return registration.get();
            } catch (RuntimeException e) {
                log.error("Could not read the SAML identity provider's metadata from {}: {}",
                        saml.getMetadataLocation(), e.toString());
                return null;
            }
        };
    }

    private static Saml2X509Credential signingCredential(SsoProperties.Saml saml) {
        require(saml.getSigningCertificateLocation(), "cert-alert.sso.saml.signing-certificate-location");
        DefaultResourceLoader resources = new DefaultResourceLoader();
        try (var key = resources.getResource(saml.getSigningKeyLocation()).getInputStream();
                var certificate = resources.getResource(saml.getSigningCertificateLocation()).getInputStream()) {
            java.security.interfaces.RSAPrivateKey privateKey = RsaKeyConverters.pkcs8().convert(key);
            java.security.cert.X509Certificate x509 = (java.security.cert.X509Certificate)
                    java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(certificate);
            return Saml2X509Credential.signing(privateKey, x509);
        } catch (IOException | java.security.cert.CertificateException e) {
            throw new IllegalStateException("Could not read the SAML signing key or certificate", e);
        }
    }

    /**
     * The entity id: as configured - an absolute URI, never a template the request fills in -
     * or {@code <public-url>/saml2/service-provider-metadata/saml}.
     */
    static String entityIdOf(SsoProperties.Saml saml, String publicUrl) {
        String configured = saml.getEntityId() == null ? "" : saml.getEntityId().trim();
        if (configured.isEmpty()) {
            return publicUrl + "/saml2/service-provider-metadata/" + SsoProperties.Saml.REGISTRATION_ID;
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(configured);
        } catch (java.net.URISyntaxException e) {
            uri = null;
        }
        if (configured.contains("{") || uri == null || !uri.isAbsolute()) {
            throw new IllegalStateException("cert-alert.sso.saml.entity-id must be an absolute URI (it is '" + configured
                    + "'); a template such as {baseUrl} would let the request's Host header decide it");
        }
        return configured;
    }

    // --- shared --------------------------------------------------------------------------

    private static Collection<String> listOf(Object claim) {
        List<String> values = new ArrayList<>();
        if (claim instanceof Collection<?> many) {
            many.stream().filter(java.util.Objects::nonNull).map(Object::toString).forEach(values::add);
        } else if (claim != null) {
            values.add(claim.toString());
        }
        return values;
    }

    private static String describe(String claim, Object value) {
        return value == null ? "nobody named by " + claim : claim + " '" + value + "'";
    }

    private static void require(String value, String property) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(property + " is required when single sign-on through it is enabled");
        }
    }
}
