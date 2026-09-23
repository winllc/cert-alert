package com.winllc.certalert.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.thymeleaf.templateresolver.FileTemplateResolver;

/**
 * With no template directory configured, nothing looks outside the jar.
 *
 * <p>Worth holding down because the setting is normally absent: a resolver built from an
 * empty value would point at whatever directory the process started in and be consulted
 * before the packaged templates on every message.
 */
@SpringBootTest
@ActiveProfiles("test")
class EmailTemplateResolverTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void noDirectoryMeansNoFileResolver() {
        assertThat(context.getBeanNamesForType(FileTemplateResolver.class)).isEmpty();
    }
}
