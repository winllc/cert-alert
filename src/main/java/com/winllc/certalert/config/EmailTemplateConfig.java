package com.winllc.certalert.config;

import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * The plain-text half of the notification emails.
 *
 * <p>Every email goes out as both HTML and text, so a client that will not render HTML - or
 * a person who has turned it off - still gets something readable. Thymeleaf picks the
 * syntax from the template mode, and a resolver carries one mode, so the text templates
 * need a resolver of their own; it joins the engine Spring Boot configures rather than
 * replacing it, and answers only for {@code email/*.txt}, leaving every page to the
 * default HTML resolver.
 */
@Configuration
public class EmailTemplateConfig {

    @Bean
    ClassLoaderTemplateResolver emailTextTemplateResolver() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        // The name carries the extension, which is what the resolvable pattern matches on.
        resolver.setSuffix("");
        resolver.setResolvablePatterns(Set.of("email/*.txt"));
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        // Ahead of the default resolver (see spring.thymeleaf.template-resolver-order), so
        // resolution never depends on the HTML one declining a .txt first.
        resolver.setOrder(1);
        return resolver;
    }
}
