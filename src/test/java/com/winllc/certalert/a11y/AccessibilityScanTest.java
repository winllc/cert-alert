package com.winllc.certalert.a11y;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * The accessibility gate: every page, scanned with axe-core in Chromium (a11y/scan.mjs), fails
 * the build on a serious or critical WCAG 2.1 A/AA violation, and checks the skip link with
 * the keyboard.
 *
 * <p>The application runs here on a real port, in its demo profile - which generates a
 * directory, sweeps it and publishes its sign-in accounts - with the test license, the
 * classification banner and the notice-and-consent banner on, so the pages carry everything
 * they can. Tagged {@code a11y} and run by {@code ./gradlew a11yTest}, not by {@code test}: it
 * needs Node and the scan's packages ({@code npm ci --prefix a11y}) and a Chromium
 * ({@code npx --prefix a11y playwright install chromium}, or {@code PLAYWRIGHT_BROWSERS_PATH}).
 */
@Tag("a11y")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cert-alert.banner.enabled=true",
            "cert-alert.banner.text=UNCLASSIFIED//FOR OFFICIAL USE ONLY",
            "ens.consent-banner.enabled=true",
            "server.ssl.enabled=false"
        })
@ActiveProfiles({"test", "demo"})
class AccessibilityScanTest {

    @LocalServerPort
    private int port;

    @Test
    void noPageHasASeriousOrCriticalViolation() throws Exception {
        File scanner = new File("a11y");
        assertThat(new File(scanner, "node_modules/@axe-core/playwright"))
                .as("the scan's packages: run npm ci --prefix a11y")
                .exists();
        Process scan = new ProcessBuilder(List.of(
                        "node", "scan.mjs", "http://localhost:" + port, new File("build/reports/a11y").getAbsolutePath()))
                .directory(scanner)
                .inheritIO()
                .start();
        assertThat(scan.waitFor(10, TimeUnit.MINUTES)).as("the scan finished").isTrue();
        assertThat(scan.exitValue())
                .as("axe-core found serious or critical violations, or could not scan a page; see build/reports/a11y")
                .isZero();
    }
}
