# syntax=docker/dockerfile:1

# cert-alert container image.
#
# Three stages: build the jar, split it into layers, assemble a runtime image from them.
# The split is what makes rebuilds cheap - the dependency layer is ~78MB and changes only
# when build.gradle.kts does, while the application layer is a couple of hundred kilobytes
# and changes on every commit. Keeping them in separate image layers means a code change
# pushes and pulls the small one.

# ---------------------------------------------------------------------------------------
# 1. Build
# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /build

# The wrapper and build scripts first: these change far less often than the source, so the
# layer holding them - and the dependency resolution below - survives most rebuilds.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
# .gitattributes keeps these LF, but a checkout made before it existed (or with
# core.autocrlf) hands over CRLF, and the kernel then looks for an interpreter "sh\r".
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

COPY src ./src

# A cache mount rather than the usual "resolve dependencies in an earlier layer" trick:
# the Gradle cache persists across builds without becoming an image layer, so nothing is
# re-downloaded and nothing is shipped. Needs BuildKit, the default in current Docker.
#
# Tests are CI's job, not the image build's: they need an in-memory LDAP server and a
# database, and repeating them here would slow every image build for no new signal.
# Build with --build-arg RUN_TESTS=true to run them anyway.
ARG RUN_TESTS=false
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    if [ "$RUN_TESTS" = "true" ]; then \
        ./gradlew --no-daemon build; \
    else \
        ./gradlew --no-daemon bootJar -x test; \
    fi
# Named precisely rather than by glob: bootJar also leaves a *-plain.jar beside the real
# one, and a wildcard would pick up both the moment the version stops saying SNAPSHOT.
RUN set -eu; \
    jar="$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -1)"; \
    test -n "$jar"; \
    cp "$jar" /build/application.jar

# ---------------------------------------------------------------------------------------
# 2. Split into layers
# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS extract

WORKDIR /extract
COPY --from=build /build/application.jar ./

# This Spring Boot version extracts to a plain jar plus a lib/ directory, with the jar's
# manifest Class-Path pointing at it - so the entrypoint is the jar itself, not a loader
# launcher. --application-filename keeps that name stable across version bumps.
RUN java -Djarmode=tools -jar application.jar extract \
        --layers --application-filename app.jar --destination extracted

# ---------------------------------------------------------------------------------------
# 3. Runtime
# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

ARG VERSION=0.0.1-SNAPSHOT
ARG REVISION=unknown
LABEL org.opencontainers.image.title="cert-alert" \
      org.opencontainers.image.description="Scrapes an LDAP directory for certificates and alerts before they expire" \
      org.opencontainers.image.source="https://github.com/winllc/cert-alert" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="MIT"

# curl is here for the health check below and for looking around inside a running
# container; nothing else is added.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# Runs as nobody in particular. The application needs no privilege: it reads LDAP, writes
# to a database, and serves HTTP.
RUN groupadd --system --gid 1001 certalert \
    && useradd --system --uid 1001 --gid certalert --home /app --shell /usr/sbin/nologin certalert

WORKDIR /app

# Copied biggest-and-most-stable first, so a code change invalidates only the last layer.
COPY --from=extract --chown=certalert:certalert /extract/extracted/dependencies/ ./
COPY --from=extract --chown=certalert:certalert /extract/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=certalert:certalert /extract/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=certalert:certalert /extract/extracted/application/ ./

# Only needed by the default H2 profile, which is for trying the thing out rather than for
# running it; a real deployment uses the postgres profile and writes nothing here.
RUN mkdir -p /app/data && chown certalert:certalert /app/data

USER certalert

# 8080 plain, 8443 when TLS is switched on for X.509 client certificates.
EXPOSE 8080 8443

# MaxRAMPercentage rather than a fixed -Xmx: the JVM reads the container's limit, so the
# heap follows whatever the orchestrator gives it.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError" \
    SERVER_PORT=8080

# Docker and Compose honour this; Kubernetes ignores it and wants its own probes against
# the same path, which is open precisely so it can be reached before anyone signs in.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://localhost:${SERVER_PORT}/actuator/health" || exit 1

# exec form, so the JVM is PID 1 and receives SIGTERM directly - which is what lets Spring
# shut down gracefully and the changelog connector stop mid-poll rather than being killed.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
