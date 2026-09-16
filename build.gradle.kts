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
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-data-ldap")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Maps DataTables' paging/ordering/search request straight onto a JPA query.
    implementation("com.github.darrachequesne:spring-data-jpa-datatables:8.0.0")

    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")

    // Front-end assets ship in the jar rather than loading from a CDN: this runs against
    // directories on networks that have no route to one.
    runtimeOnly("org.webjars:webjars-locator-lite")
    runtimeOnly("org.webjars:jquery:3.7.1")
    runtimeOnly("org.webjars:datatables:2.3.8")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    // Backs the dev profile's embedded sample directory. Excluded from the built jar.
    developmentOnly("com.unboundid:unboundid-ldapsdk")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 ships test slices as separate modules; this one provides @WebMvcTest.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // In-memory LDAP server, so the sync is tested against a real directory protocol.
    testImplementation("com.unboundid:unboundid-ldapsdk")
    // Test-only: mints certificates with exact expiry offsets for the sync tests.
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
