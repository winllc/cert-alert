package com.winllc.certalert.domain;

/** How a revocation status was arrived at. */
public enum RevocationMethod {

    /**
     * Asked about this certificate, one question and one answer. The fresher of the two,
     * and the one to use when the question is about a single certificate.
     */
    OCSP("OCSP"),

    /**
     * Read the authority's whole list and looked this serial up in it. One download answers
     * for every certificate that authority issued, which is what makes it the one to use
     * when the question is about a hundred thousand of them.
     */
    CRL("CRL");

    private final String label;

    RevocationMethod(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
