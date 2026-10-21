plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.winllc"
version = "0.0.1-SNAPSHOT"
description = "Monitors TLS certificates and raises alerts before they expire"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    // OpenSAML 5, which Spring Security's SAML 2.0 service provider is built on, is published
    // by the Shibboleth project to its own repository and not to Maven Central. Limited to
    // those groups, so nothing else can ever be resolved from it.
    maven {
        url = uri("https://build.shibboleth.net/maven/releases/")
        content {
            includeGroup("org.opensaml")
            includeGroup("net.shibboleth")
            includeGroup("net.shibboleth.utilities")
        }
    }
}

// Ahead of the versions Spring Boot 4.1.1 manages, for fixes the image scan in CI holds
// the build to: Tomcat 11.0.24 has CVE-2026-65182, CVE-2026-65905 and CVE-2026-68525
// (critical), Jackson 2.21.5 and 3.1.5 have CVE-2026-68497, -89407, -89425, -91776 and
// -91777. Drop each line once the Spring Boot in use manages that version or a later one.
extra["tomcat.version"] = "11.0.27"
extra["jackson-bom.version"] = "3.1.7"
extra["jackson-2-bom.version"] = "2.21.8"

val bouncyCastle = "1.86"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // /actuator/prometheus, on the management port only (see ManagementEndpoints).
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-data-ldap")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // X.509 authenticates most people; this backs the password fallback onto the directory.
    implementation("org.springframework.security:spring-security-ldap")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    // Single sign-on, both off until configured (see SsoProperties): OpenID Connect
    // (authorization code with PKCE) and a SAML 2.0 service provider. Used directly rather
    // than through Spring Boot's starters, whose auto-configuration would read
    // spring.security.oauth2.* and spring.security.saml2.* as well as cert-alert.sso.*.
    implementation("org.springframework.security:spring-security-oauth2-client")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.springframework.security:spring-security-saml2-service-provider")
    // OpenSAML 5.2.3 and the Shibboleth libraries under it reference Bouncy Castle and bring
    // 1.84, which has CVE-2026-8763 (critical) and CVE-2026-13506 (high). Pinned here, and named
    // outright so all three are in the boot jar whatever the transitive metadata says.
    runtimeOnly("org.bouncycastle:bcprov-jdk18on:$bouncyCastle")
    runtimeOnly("org.bouncycastle:bcpkix-jdk18on:$bouncyCastle")
    runtimeOnly("org.bouncycastle:bcutil-jdk18on:$bouncyCastle")
    constraints {
        listOf("bcprov", "bcpkix", "bcutil").forEach { module ->
            implementation("org.bouncycastle:$module-jdk18on:$bouncyCastle") {
                because("1.84, which OpenSAML brings, has CVE-2026-8763 and CVE-2026-13506")
            }
        }
    }

    // More than one replica: sessions in the database (cert-alert.session.store=jdbc), and a
    // lock in the database around every job so only one replica runs it at a time.
    implementation("org.springframework.session:spring-session-jdbc")
    implementation("net.javacrumbs.shedlock:shedlock-spring:7.10.1")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")

    // Maps DataTables' paging/ordering/search request straight onto a JPA query.
    implementation("com.github.darrachequesne:spring-data-jpa-datatables:8.0.0")

    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")

    // Front-end assets ship in the jar rather than loading from a CDN: this runs against
    // directories on networks that have no route to one.
    runtimeOnly("org.webjars:webjars-locator-lite")
    runtimeOnly("org.webjars:jquery:4.0.0")
    runtimeOnly("org.webjars:datatables:3.0.4")
    // Tabler (MIT) is the admin template the UI is built on; it bundles the Bootstrap 5
    // it is built from, so no separate Bootstrap dependency. Its icon package is 6.8MB of
    // individual SVGs, so the handful of glyphs used here are inlined as a sprite instead.
    runtimeOnly("org.webjars.npm:tabler__core:1.6.1")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    // Backs the dev profile's embedded sample directory and the demo's generated one.
    // Ships in the jar rather than being developmentOnly, because a demo is something you
    // run from the image: it stands up its own directory, and cannot do that from a
    // dependency the build left behind. It also mints the demo's certificates, which is
    // why there is no separate crypto library here.
    implementation("com.unboundid:unboundid-ldapsdk")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 ships test slices as separate modules; this one provides @WebMvcTest.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    // Test-only: mints certificates with exact expiry offsets for the sync tests.
    testImplementation("org.bouncycastle:bcpkix-jdk18on:$bouncyCastle")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// META-INF/build-info.properties: its build time is the release date a perpetual ENS
// license's maintenance is measured against (see LicensingConfiguration).
springBoot {
    buildInfo()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        // The accessibility scan needs Node and a browser; it has a task of its own, below.
        if (name == "a11yTest") includeTags("a11y") else excludeTags("a11y")
    }
    // Gradle's default for a test JVM is 512MB. The run keeps up to eight application
    // contexts (src/test/resources/spring.properties), about 300MB live at the most, and G1
    // with that little headroom spends its time failing evacuations and collecting in full.
    maxHeapSize = "1g"
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// The accessibility gate (AccessibilityScanTest): the pages scanned with axe-core in Chromium,
// failing on serious or critical WCAG 2.1 A/AA violations. Needs `npm ci --prefix a11y` and a
// Chromium for Playwright first; CI's a11y job does both. Reports in build/reports/a11y.
val a11yTest by tasks.registering(Test::class) {
    description = "Scans every page with axe-core in Chromium"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    outputs.dir(layout.buildDirectory.dir("reports/a11y"))
    outputs.upToDateWhen { false }
}
