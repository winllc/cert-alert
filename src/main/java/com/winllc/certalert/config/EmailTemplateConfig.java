package com.winllc.certalert.config;

import java.nio.file.Path;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.FileTemplateResolver;

/**
 * Where the notification emails are rendered from.
 *
 * <p>Every email goes out as both HTML and text, so a client that will not render HTML - or
 * a person who has turned it off - still gets something readable. Thymeleaf picks the
 * syntax from the template mode and a resolver carries one mode, which is why the text
 * templates need a resolver of their own rather than sharing the engine's.
 *
 * <p>Four resolvers, and the order is the whole design - each is asked in turn until one
 * answers, so what is first decides what wins:
 *
 * <ol>
 *   <li>1, 2: a deployment's own templates, from a directory outside the jar, when
 *       {@code cert-alert.notifications.email.template-directory} names one. Both check
 *       the file exists before claiming the template, so a directory holding one override
 *       overrides one template and the rest still come from the jar.
 *   <li>3: the packaged plain-text templates.
 *   <li>4: the packaged HTML - the engine Spring Boot configures, which serves every page
 *       as well and so is asked last (see {@code spring.thymeleaf.template-resolver-order}).
 * </ol>
 */
@Configuration
public class EmailTemplateConfig {

    private static final Logger log = LoggerFactory.getLogger(EmailTemplateConfig.class);

    /** The name carries the extension for text, which is what the pattern matches on. */
    private static final String TEXT_PATTERN = "email/*.txt";

    /** HTML templates are asked for without one, the suffix being the resolver's. */
    private static final String HTML_PATTERN = "email/*";

    /**
     * A deployment's own plain-text templates.
     *
     * <p>Ahead of the HTML one because a text template is named with its extension and the
     * HTML resolver would otherwise be asked for {@code expiring-user.txt.html} first -
     * harmless, since it checks, but asking in the right order costs nothing.
     */
    @Bean
    @ConditionalOnProperty(prefix = "cert-alert.notifications.email", name = "template-directory")
    FileTemplateResolver customEmailTextTemplateResolver(NotificationProperties properties) {
        FileTemplateResolver resolver = fileResolver(properties, TemplateMode.TEXT, TEXT_PATTERN, "", 1);
        // Once, from the first of the two, and at info: a directory that is not there is
        // otherwise silent - every template falls back and the deployment looks as though
        // it is ignoring the setting.
        log.info("Email templates will be read from {} before the packaged ones", resolver.getPrefix());
        return resolver;
    }

    /** And its HTML ones. */
    @Bean
    @ConditionalOnProperty(prefix = "cert-alert.notifications.email", name = "template-directory")
    FileTemplateResolver customEmailHtmlTemplateResolver(NotificationProperties properties) {
        return fileResolver(properties, TemplateMode.HTML, HTML_PATTERN, ".html", 2);
    }

    /** The packaged plain-text templates. */
    @Bean
    ClassLoaderTemplateResolver emailTextTemplateResolver() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix("");
        resolver.setResolvablePatterns(Set.of(TEXT_PATTERN));
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        resolver.setOrder(3);
        return resolver;
    }

    private FileTemplateResolver fileResolver(
            NotificationProperties properties, TemplateMode mode, String pattern, String suffix, int order) {

        String configured = properties.getEmail().getTemplateDirectory();
        if (configured == null || configured.isBlank()) {
            // Reachable only by setting the property to nothing, which is not a way of
            // turning it off - leaving it unset is. Said plainly at startup, because the
            // alternative is a resolver silently pointing at the working directory.
            throw new IllegalStateException(
                    "cert-alert.notifications.email.template-directory is set to an empty value. "
                            + "Remove it to use the packaged templates, or name a directory.");
        }
        // Absolute, so what is logged is what was looked at rather than something relative
        // to whatever directory the process happens to have started in.
        Path directory = Path.of(configured.trim()).toAbsolutePath();

        FileTemplateResolver resolver = new FileTemplateResolver();
        resolver.setPrefix(directory + "/");
        resolver.setSuffix(suffix);
        resolver.setResolvablePatterns(Set.of(pattern));
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding("UTF-8");
        // The point of the whole arrangement: a template this directory does not hold is
        // not claimed, and the packaged one answers instead.
        resolver.setCheckExistence(true);
        resolver.setCacheable(true);
        resolver.setOrder(order);
        return resolver;
    }
}
