package com.winllc.certalert.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The API's OpenAPI 3.1 document, at a path that does not move between releases. Served to
 * anyone, signed in or not: it describes the product, not this installation's data.
 */
@RestController
public class OpenApiController {

    public static final String PATH = "/api/v1/openapi.yaml";

    static final String RESOURCE = "openapi/cert-alert-v1.yaml";

    private final String document;

    public OpenApiController() throws IOException {
        this.document = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
    }

    @GetMapping(PATH)
    public ResponseEntity<String> document() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/yaml;charset=UTF-8"))
                .cacheControl(CacheControl.noCache())
                .body(document);
    }
}
