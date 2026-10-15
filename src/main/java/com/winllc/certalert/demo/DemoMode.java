package com.winllc.certalert.demo;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Whether this copy is a demo, for the two configurations that have to disagree about it.
 *
 * <p>Written as a condition rather than as another {@code @ConditionalOnProperty} because
 * the ordinary security configuration already carries one, and the two have to be read
 * together: security on, demo off. A second annotation of the same kind is not the way to
 * say that.
 */
public final class DemoMode {

    static final String PROPERTY = "cert-alert.demo.enabled";

    private DemoMode() {}

    private static boolean on(ConditionContext context) {
        return context.getEnvironment().getProperty(PROPERTY, Boolean.class, false);
    }

    /** Matches where the demo is switched on. */
    public static final class On implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return on(context);
        }
    }

    /** Matches everywhere else, which is everywhere that matters. */
    public static final class Off implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !on(context);
        }
    }
}
