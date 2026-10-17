package com.winllc.certalert.revocation;

import com.winllc.certalert.config.RevocationProperties;
import com.winllc.certalert.security.EnsX509Properties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one {@link FetchPolicy} every revocation download is held to - the nightly check's and
 * sign-in's alike - so there is one allowlist to read and one place it is decided.
 */
@Configuration(proxyBeanMethods = false)
public class FetchPolicyConfig {

    @Bean
    public FetchPolicy revocationFetchPolicy(RevocationProperties revocation, EnsX509Properties x509) {
        // The responders and list configured here are somebody's decision, not a
        // certificate's, so their hosts are allowed whatever else is.
        return FetchPolicy.of(
                revocation.getAllowedHosts(),
                revocation.getDefaultOcspUrl(),
                revocation.getDefaultCrlUrl(),
                x509.getOcspResponderUrl());
    }
}
