package com.winllc.certalert.service;

import java.security.AlgorithmParameters;
import java.security.cert.X509Certificate;
import java.security.spec.PSSParameterSpec;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the algorithms out of a certificate in a form worth reporting on.
 *
 * <p>The signature algorithm is cached as the provider names it - {@code SHA256withRSA} -
 * which is one string carrying two facts. A report asking "what is still signed with SHA-1"
 * should not have to pattern-match that string, and would get it wrong for RSASSA-PSS,
 * where the name says nothing about the digest at all: it is in the signature parameters.
 * So the digest is pulled out and stored on its own.
 */
final class CertificateAlgorithms {

    private static final Logger log = LoggerFactory.getLogger(CertificateAlgorithms.class);

    private static final String PSS_OID = "1.2.840.113549.1.1.10";

    /** {@code SHA256withRSA}, {@code SHA1withDSA}, {@code SHA256WITHRSAANDMGF1}. */
    private static final Pattern WITH = Pattern.compile("(?i)^(.+?)with.+$");

    /**
     * SHA3 first, and the dash after the 3 is required: without it this pattern also reads
     * SHA384 as SHA-3 truncated to 84 bits, which is not a thing.
     */
    private static final Pattern SHA3 = Pattern.compile("(?i)^SHA-?3-(\\d+)$");

    private static final Pattern SHA2 = Pattern.compile("(?i)^SHA-?(\\d+(?:/\\d+)?)$");

    private CertificateAlgorithms() {}

    /**
     * The digest the signature was made over, canonically named: {@code SHA-256},
     * {@code SHA-1}, {@code SHA3-512}.
     *
     * <p>Null where there is no separate digest to name. Ed25519 and Ed448 are the honest
     * case of that: the hash is part of the scheme rather than a choice made about the
     * certificate, and recording one would invite a report to treat it as a comparable
     * setting.
     */
    static String hashAlgorithm(X509Certificate certificate) {
        String name = certificate.getSigAlgName();
        if (name != null) {
            Matcher matcher = WITH.matcher(name);
            if (matcher.matches()) {
                return canonical(matcher.group(1));
            }
        }
        if (PSS_OID.equals(certificate.getSigAlgOID()) || (name != null && name.toUpperCase(Locale.ROOT)
                .startsWith("RSASSA-PSS"))) {
            return pssDigest(certificate);
        }
        return null;
    }

    /**
     * RSASSA-PSS names no digest in its algorithm: the one it used is in the signature
     * parameters, alongside the mask function and salt length.
     */
    private static String pssDigest(X509Certificate certificate) {
        byte[] encoded = certificate.getSigAlgParams();
        if (encoded == null) {
            // Absent parameters mean the defaults, and the default for PSS is SHA-1.
            return "SHA-1";
        }
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("RSASSA-PSS");
            parameters.init(encoded);
            return canonical(parameters.getParameterSpec(PSSParameterSpec.class).getDigestAlgorithm());
        } catch (Exception e) {
            log.debug("Could not read the digest out of the RSASSA-PSS parameters", e);
            return null;
        }
    }

    /**
     * One spelling per digest, so a report can group on it. Providers are not consistent:
     * the same digest arrives as SHA256, SHA-256 or sha256 depending on who named it.
     */
    private static String canonical(String digest) {
        if (digest == null || digest.isBlank()) {
            return null;
        }
        String trimmed = digest.trim();
        Matcher sha3 = SHA3.matcher(trimmed);
        if (sha3.matches()) {
            return "SHA3-" + sha3.group(1);
        }
        Matcher sha2 = SHA2.matcher(trimmed);
        if (sha2.matches()) {
            return "SHA-" + sha2.group(1);
        }
        // Anything else - MD5, RIPEMD160, SHAKE256 - is reported as the provider named it.
        return trimmed.toUpperCase(Locale.ROOT);
    }
}
