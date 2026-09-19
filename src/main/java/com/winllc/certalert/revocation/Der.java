package com.winllc.certalert.revocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Just enough DER to read the two extensions that say where to ask about revocation.
 *
 * <p>The JDK parses the extensions it needs for path validation and keeps the parsers to
 * itself; everything else arrives as {@code getExtensionValue}, which hands back the raw
 * encoding. What is wanted here is small and shallow - the URIs out of a
 * CRLDistributionPoints, the OCSP responder out of an AuthorityInfoAccess - so this walks
 * the tag-length-value structure rather than pulling in a full ASN.1 library for it.
 *
 * <p>Deliberately incomplete. It reads definite-length DER, which is all a certificate may
 * contain, and it understands structure rather than types: a constructed value is something
 * to walk into and a primitive one is bytes. Anything it cannot make sense of comes back
 * empty, because a certificate whose extensions will not parse is a certificate whose
 * revocation cannot be checked - not a reason to fail a sweep.
 */
final class Der {

    /** Bit 6 of the identifier octet: set means the value is itself a sequence of values. */
    private static final int CONSTRUCTED = 0x20;

    private static final int TAG_MASK = 0x1F;

    private Der() {}

    /** One tag-length-value. */
    record Tlv(int tag, byte[] value) {

        boolean isConstructed() {
            return (tag & CONSTRUCTED) != 0;
        }

        /** The tag number without its class and constructed bits: {@code [6]} is 6. */
        int number() {
            return tag & TAG_MASK;
        }

        /** Whether this is a context-specific tag of this number, constructed or not. */
        boolean isContext(int number) {
            return (tag & 0xC0) == 0x80 && number() == number;
        }

        List<Tlv> children() {
            return isConstructed() ? parse(value) : List.of();
        }
    }

    /** The values at the top level of this encoding, or empty where it will not parse. */
    static List<Tlv> parse(byte[] der) {
        List<Tlv> values = new ArrayList<>();
        int at = 0;
        while (at < der.length) {
            int tag = der[at++] & 0xFF;
            if (at >= der.length) {
                return values;
            }
            int first = der[at++] & 0xFF;
            int length;
            if ((first & 0x80) == 0) {
                length = first;
            } else {
                int octets = first & 0x7F;
                // Indefinite length, or a length longer than anything in a certificate.
                if (octets == 0 || octets > 4 || at + octets > der.length) {
                    return values;
                }
                length = 0;
                for (int i = 0; i < octets; i++) {
                    length = (length << 8) | (der[at++] & 0xFF);
                }
            }
            if (length < 0 || at + length > der.length) {
                return values;
            }
            byte[] value = new byte[length];
            System.arraycopy(der, at, value, 0, length);
            at += length;
            values.add(new Tlv(tag, value));
        }
        return values;
    }

    /**
     * An extension value as {@code X509Certificate.getExtensionValue} returns it: the
     * extension's own encoding wrapped in an OCTET STRING.
     */
    static List<Tlv> unwrapExtension(byte[] extensionValue) {
        if (extensionValue == null) {
            return List.of();
        }
        List<Tlv> outer = parse(extensionValue);
        if (outer.size() != 1) {
            return List.of();
        }
        return parse(outer.getFirst().value());
    }

    /**
     * Every context-specific value with this tag number, however deeply nested.
     *
     * <p>Which is how the URIs are found: a GeneralName's uniformResourceIdentifier is
     * {@code [6]}, and it sits at the bottom of two or three layers of optional structure
     * that differ between a CRLDistributionPoints and an AuthorityInfoAccess.
     */
    static List<byte[]> collectContext(List<Tlv> values, int number) {
        List<byte[]> found = new ArrayList<>();
        for (Tlv value : values) {
            if (value.isContext(number) && !value.isConstructed()) {
                found.add(value.value());
            }
            if (value.isConstructed()) {
                found.addAll(collectContext(value.children(), number));
            }
        }
        return found;
    }
}
