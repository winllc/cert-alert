package com.winllc.certalert.security.sso;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single sign-on, bound from {@code cert-alert.sso}: OpenID Connect and a SAML 2.0 service
 * provider. Both are off until configured, and with both off nothing about signing in
 * changes.
 *
 * <p>Whoever an identity provider signs in is matched to a person in the directory exactly
 * as the other ways in are - by the DN of their entry, or by their uid - and never by an
 * address or a name. Somebody the directory does not hold is refused: the identity
 * provider says who somebody is; the directory says whether they are somebody this
 * application serves. Their roles come from the directory and the configuration, as for
 * anybody else, and from the provider's groups only where that provider's own
 * {@code admin-groups} or {@code auditor-groups} names them - lists kept apart from the
 * directory's groups, so a group somebody can create at the provider can never be mistaken
 * for a directory group of the same name.
 *
 * <p>Every address the providers are given - the OIDC redirect URI, the SAML entity id and
 * assertion consumer - is built from {@link #publicUrl}, never from the request: a
 * {@code Host} or {@code Forwarded} header a client sets cannot change what a response must
 * be addressed to.
 */
@ConfigurationProperties(prefix = "cert-alert.sso")
public class SsoProperties {

    /**
     * The address people reach this application at, e.g. {@code https://certalert.example.gov}:
     * what the providers are told to send people back to, and what a SAML response must be
     * addressed to. Required when either provider is enabled.
     */
    private String publicUrl;

    private final Oidc oidc = new Oidc();
    private final Saml saml = new Saml();

    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        this.publicUrl = publicUrl;
    }

    /**
     * {@link #publicUrl}, checked: absolute http(s), no template, no query, without a trailing
     * slash.
     *
     * @throws IllegalStateException where it is missing or is not one
     */
    public String requirePublicUrl() {
        String value = publicUrl == null ? "" : publicUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(value);
        } catch (java.net.URISyntaxException e) {
            uri = null;
        }
        if (value.isEmpty() || value.contains("{") || uri == null || uri.getHost() == null
                || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalStateException("cert-alert.sso.public-url (CERT_ALERT_SSO_PUBLIC_URL) must be the absolute "
                    + "address people reach this application at, e.g. https://certalert.example.gov, when single "
                    + "sign-on is enabled; it is '" + publicUrl + "'");
        }
        return value;
    }

    public Oidc getOidc() {
        return oidc;
    }

    public Saml getSaml() {
        return saml;
    }

    /** How the name a provider gives is matched to a person in the directory. */
    public enum Match {
        /** It is their uid. */
        UID,
        /** It is the DN of their entry. */
        DN
    }

    /** What both kinds of provider share. */
    public abstract static class Provider {

        private boolean enabled;

        /** On the sign-in page's button. */
        private String displayName = "Single sign-on";

        private Match match = Match.UID;

        /** How far apart this application's clock and the provider's may be. */
        private Duration clockSkew = Duration.ofMinutes(2);

        /**
         * Groups, as this provider names them, whose members are administrators. Only this
         * provider's groups are matched against it, never the directory's.
         */
        private java.util.List<String> adminGroups = new ArrayList<>();

        /** Groups, as this provider names them, whose members are auditors. */
        private java.util.List<String> auditorGroups = new ArrayList<>();

        public java.util.List<String> getAdminGroups() {
            return adminGroups;
        }

        public void setAdminGroups(java.util.List<String> adminGroups) {
            this.adminGroups = adminGroups;
        }

        public java.util.List<String> getAuditorGroups() {
            return auditorGroups;
        }

        public void setAuditorGroups(java.util.List<String> auditorGroups) {
            this.auditorGroups = auditorGroups;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public Match getMatch() {
            return match;
        }

        public void setMatch(Match match) {
            this.match = match;
        }

        public Duration getClockSkew() {
            return clockSkew;
        }

        public void setClockSkew(Duration clockSkew) {
            this.clockSkew = clockSkew;
        }
    }

    /**
     * OpenID Connect: the authorization code flow, always with PKCE, the ID token's issuer,
     * audience, signature, expiry and nonce checked.
     */
    public static class Oidc extends Provider {

        /** The registration id, in the redirect URI: {@code <public-url>/login/oauth2/code/oidc}. */
        public static final String REGISTRATION_ID = "oidc";

        /** The issuer; its discovery document is read at startup. Must match the tokens' iss exactly. */
        private String issuerUri;

        private String clientId;

        /** Empty for a public client, which then authenticates with PKCE alone. */
        private String clientSecret;

        private List<String> scopes = new ArrayList<>(List.of("openid", "profile"));

        /** The claim holding the uid or DN (see {@code match}). */
        private String usernameClaim = "preferred_username";

        /** The claim listing the person's groups; empty reads none. */
        private String groupsClaim = "";

        /** The ID token signature algorithm accepted (RS256, ES256, ...). */
        private String jwsAlgorithm = "RS256";

        /**
         * Whether signing out of CertAlert also signs out at the provider (OpenID Connect
         * RP-initiated logout, at the {@code end_session_endpoint} its discovery document names),
         * which then sends the browser back to {@code <public-url>/login?logout} - the
         * post-logout redirect URI to register with it. Off by default so an upgrade signs out
         * as before; new installations' settings turn it on.
         */
        private boolean providerLogout;

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public List<String> getScopes() {
            return scopes;
        }

        public void setScopes(List<String> scopes) {
            this.scopes = scopes;
        }

        public String getUsernameClaim() {
            return usernameClaim;
        }

        public void setUsernameClaim(String usernameClaim) {
            this.usernameClaim = usernameClaim;
        }

        public String getGroupsClaim() {
            return groupsClaim;
        }

        public void setGroupsClaim(String groupsClaim) {
            this.groupsClaim = groupsClaim;
        }

        public String getJwsAlgorithm() {
            return jwsAlgorithm;
        }

        public boolean isProviderLogout() {
            return providerLogout;
        }

        public void setProviderLogout(boolean providerLogout) {
            this.providerLogout = providerLogout;
        }

        public void setJwsAlgorithm(String jwsAlgorithm) {
            this.jwsAlgorithm = jwsAlgorithm;
        }
    }

    /**
     * A SAML 2.0 service provider: signed responses or assertions only, from the configured
     * identity provider, in response to a request this application made (no unsolicited,
     * identity-provider-initiated sign-in), for this service provider, in date.
     */
    public static class Saml extends Provider {

        /** The registration id: {@code <public-url>/login/saml2/sso/saml} is the assertion consumer. */
        public static final String REGISTRATION_ID = "saml";

        /** The identity provider's metadata: an https:// URL, or file: for a copy on disk. */
        private String metadataLocation;

        /**
         * This service provider's entity id, as registered with the identity provider: an
         * absolute URI. Empty means {@code <public-url>/saml2/service-provider-metadata/saml}.
         * Never a template filled in from the request.
         */
        private String entityId = "";

        /**
         * A PEM private key and certificate to sign authentication requests with, for an
         * identity provider that wants them signed (file: locations). Optional.
         */
        private String signingKeyLocation;

        private String signingCertificateLocation;

        /**
         * SAML single logout: signing out of CertAlert sends the identity provider a signed
         * {@code LogoutRequest}, and a {@code LogoutRequest} from it (another application
         * signing out) ends the session here, answered with a signed {@code LogoutResponse} - at
         * {@code <public-url>/logout/saml2/slo}, HTTP-Redirect binding. Needs the signing key and
         * certificate above, and works only with an identity provider whose metadata names a
         * {@code SingleLogoutService}; without either, signing out stays local, as it does with
         * this off. Off by default so an upgrade signs out as before; new installations'
         * settings turn it on.
         */
        private boolean singleLogout;

        /** The attribute holding the uid or DN (see {@code match}); empty uses the NameID. */
        private String usernameAttribute = "";

        /** The attribute listing the person's groups; empty reads none. */
        private String groupsAttribute = "";

        public String getMetadataLocation() {
            return metadataLocation;
        }

        public void setMetadataLocation(String metadataLocation) {
            this.metadataLocation = metadataLocation;
        }

        public String getEntityId() {
            return entityId;
        }

        public void setEntityId(String entityId) {
            this.entityId = entityId;
        }

        public String getSigningKeyLocation() {
            return signingKeyLocation;
        }

        public void setSigningKeyLocation(String signingKeyLocation) {
            this.signingKeyLocation = signingKeyLocation;
        }

        public String getSigningCertificateLocation() {
            return signingCertificateLocation;
        }

        public void setSigningCertificateLocation(String signingCertificateLocation) {
            this.signingCertificateLocation = signingCertificateLocation;
        }

        public boolean isSingleLogout() {
            return singleLogout;
        }

        public void setSingleLogout(boolean singleLogout) {
            this.singleLogout = singleLogout;
        }

        /** Whether single logout is on and has the key it signs with. */
        public boolean singleLogoutConfigured() {
            return singleLogout && signingKeyLocation != null && !signingKeyLocation.isBlank();
        }

        public String getUsernameAttribute() {
            return usernameAttribute;
        }

        public void setUsernameAttribute(String usernameAttribute) {
            this.usernameAttribute = usernameAttribute;
        }

        public String getGroupsAttribute() {
            return groupsAttribute;
        }

        public void setGroupsAttribute(String groupsAttribute) {
            this.groupsAttribute = groupsAttribute;
        }
    }
}
