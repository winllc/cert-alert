# syntax=docker/dockerfile:1

# cert-alert container image.
#
# Three stages: build the jar, split it into layers, assemble a runtime image from them.
# The split is what makes rebuilds cheap - the dependency layer is ~78MB and changes only
# when build.gradle.kts does, while the application layer is a couple of hundred kilobytes
# and changes on every commit. Keeping them in separate image layers means a code change
# pushes and pulls the small one.
#
# Built on Red Hat's Universal Base Image. UBI is freely redistributable and needs no
# subscription to pull or to run, and it is what a RHEL or OpenShift estate already has a
# patching story for - which for a deployment inside an accredited network is worth more
# than a smaller image.
#
# The tags are arguments rather than literals for two reasons: a real build should pin to
# a digest or a dated tag rather than to a stream, and moving to UBI 10 - whose OpenJDK
# images are the same shape - is then a build argument rather than an edit. The floating
# stream is the default so that an unpinned build keeps getting patched bases.
ARG UBI_JDK_IMAGE=registry.access.redhat.com/ubi9/openjdk-21:latest
ARG UBI_JRE_IMAGE=registry.access.redhat.com/ubi9/openjdk-21-runtime:latest

# ---------------------------------------------------------------------------------------
# 1. Build
# ---------------------------------------------------------------------------------------
FROM ${UBI_JDK_IMAGE} AS build

# These images run as uid 185 and their home is not writable for a Gradle build; nothing
# built here is shipped, so the build stage is root and the runtime stage is not.
USER root
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
# bootJar also leaves a *-plain.jar beside the real one, and taking the first jar in the
# directory would pick up whichever of the two the shell listed first. Written with a
# shell glob rather than find, because a minimal base is not obliged to carry findutils
# and a build that depends on it fails only once somebody changes the base image.
RUN set -eu; \
    jar=""; \
    for candidate in build/libs/*.jar; do \
        case "$candidate" in *-plain.jar) continue ;; esac; \
        jar="$candidate"; \
        break; \
    done; \
    test -n "$jar"; \
    cp "$jar" /build/application.jar

# ---------------------------------------------------------------------------------------
# 2. Split into layers
# ---------------------------------------------------------------------------------------
FROM ${UBI_JDK_IMAGE} AS extract

USER root
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
FROM ${UBI_JRE_IMAGE}

USER root

# Re-declared: an argument set before the first FROM reaches the FROM lines and nothing
# else, so the label below would otherwise read as empty.
ARG UBI_JRE_IMAGE
ARG VERSION=0.0.1-SNAPSHOT
ARG REVISION=unknown
LABEL org.opencontainers.image.title="cert-alert" \
      org.opencontainers.image.description="Scrapes an LDAP directory for certificates and alerts before they expire" \
      org.opencontainers.image.source="https://github.com/winllc/cert-alert" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.base.name="${UBI_JRE_IMAGE}"

# curl is for the health check below. The minimal UBI these images are built on ships
# curl-minimal, which provides it - so this asks whether it is there rather than
# installing a second curl over the top of the one that is.
RUN if ! command -v curl >/dev/null 2>&1; then \
        microdnf install -y --nodocs curl && microdnf clean all; \
    fi

# No user is created: the image already has one, uid 185, and it is the one Red Hat's
# tooling and its own documentation expect. Creating another would also mean installing
# shadow-utils, which the minimal base deliberately leaves out.
#
# Everything is owned by that uid and group 0, and given the group the same permissions as
# the owner. That is what makes the image work under OpenShift's default policy, which
# runs a container as an arbitrary uid that is always a member of group 0 - an image that
# only its own uid can write to starts and then cannot write anything.
WORKDIR /app

# Copied biggest-and-most-stable first, so a code change invalidates only the last layer.
COPY --from=extract --chown=185:0 /extract/extracted/dependencies/ ./
COPY --from=extract --chown=185:0 /extract/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=185:0 /extract/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=185:0 /extract/extracted/application/ ./

# Only needed by the default H2 profile, which is for trying the thing out rather than for
# running it; a real deployment uses the postgres profile and writes nothing here.
RUN mkdir -p /app/data \
    && chown -R 185:0 /app \
    && chmod -R g=u /app

USER 185

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
#
# These images set an ENTRYPOINT of their own for source-to-image builds; this replaces it,
# because what is being run here is a jar that was built somewhere else.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
