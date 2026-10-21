package com.winllc.certalert.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.yaml.snakeyaml.Yaml;

/**
 * The OpenAPI document, held to the code - which is what keeps a hand-written document from
 * drifting into fiction:
 *
 * <ul>
 *   <li>every operation it documents is one the application serves;
 *   <li>every endpoint a token may call ({@link ApiEndpoints}) is documented, with the scope
 *       the filter chain enforces in {@code x-required-scope} and in its security requirement;
 *   <li>and it is served, to anybody, at its stable path.
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class OpenApiDocumentTest {

    private static final Set<String> METHODS = Set.of("get", "post", "put", "delete", "patch");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    record Operation(HttpMethod method, String path, String scope) {}

    @SuppressWarnings("unchecked")
    private static List<Operation> documented() throws Exception {
        Map<String, Object> document;
        try (InputStream in = new ClassPathResource(OpenApiController.RESOURCE).getInputStream()) {
            document = new Yaml().load(in);
        }
        assertThat(document.get("openapi")).isEqualTo("3.1.0");
        List<Operation> operations = new ArrayList<>();
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) document.get("paths");
        paths.forEach((path, item) -> item.forEach((method, value) -> {
            if (!METHODS.contains(method)) {
                return;
            }
            Map<String, Object> operation = (Map<String, Object>) value;
            String scope = (String) operation.get("x-required-scope");
            List<Map<String, List<String>>> security = (List<Map<String, List<String>>>) operation.get("security");
            assertThat(security).as("%s %s has a security requirement", method, path).isNotNull();
            assertThat(security.getFirst().get("apiToken")).as("%s %s names its scope", method, path).containsExactly(scope);
            operations.add(new Operation(HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)), path, scope));
        }));
        return operations;
    }

    @Test
    void everyDocumentedOperationIsServed() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Operation operation : documented()) {
            String concrete = operation.path().replaceAll("\\{[^}]+}", "1");
            boolean served = mappings.getHandlerMethods().keySet().stream().anyMatch(info -> serves(info, operation.method(), concrete));
            if (!served) {
                missing.add(operation.method() + " " + operation.path());
            }
        }
        assertThat(missing).as("documented but not served").isEmpty();
    }

    @Test
    void everyEndpointATokenMayCallIsDocumentedWithItsScope() throws Exception {
        Map<String, String> documented = new LinkedHashMap<>();
        for (Operation operation : documented()) {
            documented.put(operation.method() + " " + operation.path().replaceAll("\\{[^}]+}", "*"), operation.scope());
        }
        Set<String> undocumented = new LinkedHashSet<>();
        for (ApiEndpoints.Rule rule : ApiEndpoints.RULES) {
            String key = rule.method() + " " + rule.pattern();
            String scope = documented.get(key);
            if (scope == null) {
                undocumented.add(key);
            } else {
                assertThat(scope).as("the scope documented for %s", key).isEqualTo(rule.scope().id());
            }
        }
        assertThat(undocumented).as("callable with a token but not documented").isEmpty();
        // And nothing documented as callable with a token that the chain would refuse.
        Set<String> rules = new LinkedHashSet<>();
        ApiEndpoints.RULES.forEach(rule -> rules.add(rule.method() + " " + rule.pattern()));
        assertThat(documented.keySet()).allMatch(rules::contains, "is an ApiEndpoints rule");
    }

    @Test
    void theDocumentIsServedAtItsStablePathToAnybody() throws Exception {
        mockMvc.perform(get(OpenApiController.PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/yaml"))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("openapi: 3.1.0")));
    }

    private static boolean serves(RequestMappingInfo info, HttpMethod method, String path) {
        if (!info.getMethodsCondition().getMethods().isEmpty()
                && info.getMethodsCondition().getMethods().stream().noneMatch(m -> m.name().equals(method.name()))) {
            return false;
        }
        if (info.getPathPatternsCondition() == null) {
            return false;
        }
        for (PathPattern pattern : info.getPathPatternsCondition().getPatterns()) {
            if (PathPatternParser.defaultInstance.parse(pattern.getPatternString())
                    .matches(org.springframework.http.server.PathContainer.parsePath(path))) {
                return true;
            }
        }
        return false;
    }
}
