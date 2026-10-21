package com.winllc.certalert.session;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ConfigurableObjectInputStream;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.serializer.support.SerializingConverter;

/**
 * How session attributes are written to and read back from the database.
 *
 * <p>Written with Java serialization, as Spring Session does. Read back through an allow-list
 * ({@link #ALLOWED}): only the types this application actually keeps in a session - strings,
 * numbers, times and collections, Spring Security's authentication, CSRF, saved-request and
 * OAuth2/SAML types, Spring MVC's flash map, and this application's own principals - and
 * nothing else. A stored value naming any other class (a deserialization gadget such as
 * {@code java.util.PriorityQueue}, anything in {@code java.rmi}, {@code java.net.URL}) is
 * refused, and the attribute reads as absent rather than failing the request: a forged or
 * stale attribute costs the session that value, never the server.
 */
public final class SessionAttributeSerialization {

    private static final Logger log = LoggerFactory.getLogger(SessionAttributeSerialization.class);

    /** What a stored session attribute may be made of. Order matters: the first match decides. */
    public static final ObjectInputFilter ALLOWED = ObjectInputFilter.Config.createFilter(String.join(";",
            "maxdepth=32",
            "maxrefs=100000",
            "maxbytes=4194304",
            "maxarray=100000",
            "!java.util.PriorityQueue",
            "!java.rmi.**",
            "!java.net.**",
            "java.lang.String",
            "java.lang.String$CaseInsensitiveComparator",
            "java.lang.Boolean",
            "java.lang.Number",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Short",
            "java.lang.Byte",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Character",
            "java.lang.Enum",
            "java.lang.Object",
            "java.lang.StackTraceElement",
            "java.math.BigInteger",
            "java.math.BigDecimal",
            "java.time.*",
            "java.util.*",
            "java.security.cert.Certificate$CertificateRep",
            "javax.security.auth.x500.X500Principal",
            "org.springframework.security.**",
            "org.springframework.web.servlet.FlashMap",
            "org.springframework.util.LinkedMultiValueMap",
            "org.springframework.util.MultiValueMapAdapter",
            "com.winllc.certalert.**",
            "!*"));

    private SessionAttributeSerialization() {}

    public static Converter<Object, byte[]> serializer() {
        return new SerializingConverter();
    }

    /** Reads an attribute back; null - absent - where it is refused or cannot be read. */
    public static Converter<byte[], Object> deserializer(ClassLoader classLoader) {
        return bytes -> read(bytes, classLoader);
    }

    public static Object read(byte[] bytes, ClassLoader classLoader) {
        if (bytes == null) {
            return null;
        }
        try (ObjectInputStream in = new ConfigurableObjectInputStream(new ByteArrayInputStream(bytes), classLoader)) {
            in.setObjectInputFilter(info -> {
                ObjectInputFilter.Status status = ALLOWED.checkInput(info);
                if (status == ObjectInputFilter.Status.REJECTED) {
                    log.warn("A stored session attribute names {}, which is not on the allow-list",
                            info.serialClass() == null ? "a value over the limits" : info.serialClass().getName());
                }
                return status;
            });
            return in.readObject();
        } catch (Exception | LinkageError e) {
            log.warn("A stored session attribute was not read back, and is treated as absent: {}", e.toString());
            return null;
        }
    }
}
