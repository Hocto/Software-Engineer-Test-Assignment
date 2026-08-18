# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1 — build
#
# Based on Corretto 25 rather than a gradle:* image so the JDK already matches the
# toolchain declared in build.gradle (Java 25, vendor Amazon). A mismatched base would
# make Gradle download a second JDK on every image build.
#
# Build files are copied before src/ so the dependency layer is only invalidated when
# the build script changes, not on every source edit.
# ---------------------------------------------------------------------------
FROM amazoncorretto:25-alpine AS builder

# binutils supplies the objcopy that jlink --strip-debug shells out to.
RUN apk add --no-cache binutils

WORKDIR /build

COPY gradle/ gradle/
COPY gradlew settings.gradle build.gradle ./
RUN ./gradlew --no-daemon --quiet dependencies --configuration runtimeClasspath

COPY src/ src/
# Tests run in CI against Testcontainers; the image build must not require a
# Docker-in-Docker socket.
RUN ./gradlew --no-daemon --quiet bootJar -x test

# Split the fat jar into Spring Boot's layers so dependency layers stay cached
# across rebuilds while only the application layer changes. The jar is renamed to a
# fixed app.jar so the entrypoint does not have to track the project version.
RUN JAR=$(ls build/libs/*.jar | grep -v plain) \
    && java -Djarmode=tools -jar "$JAR" extract --layers --destination /extracted \
    && mv /extracted/application/*.jar /extracted/application/app.jar

# Build a stripped runtime instead of shipping the full JDK image.
#
# ALL-MODULE-PATH rather than a jdeps-derived module list: Spring resolves plenty of
# classes reflectively (java.beans is the one that bites first), so a "minimal" runtime
# is really a bet that no untested code path touches a module jdeps could not see. The
# savings from a curated list are not worth a ClassNotFoundException surfacing in
# production, so take the guaranteed-complete runtime and get the size back from
# stripping debug symbols and compressing instead.
RUN jlink \
        --module-path "$JAVA_HOME/jmods" \
        --add-modules ALL-MODULE-PATH \
        --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
        --output /javaruntime

# ---------------------------------------------------------------------------
# Stage 2 — runtime
# ---------------------------------------------------------------------------
FROM alpine:3.21 AS runtime

# musl-compatible libs the jlink runtime links against.
RUN apk add --no-cache libstdc++ zlib

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=builder /javaruntime ${JAVA_HOME}

# Runs unprivileged: a container escape from the app process should not land as root.
RUN addgroup -S banking && adduser -S -G banking banking

WORKDIR /app

COPY --from=builder --chown=banking:banking /extracted/dependencies/ ./
COPY --from=builder --chown=banking:banking /extracted/spring-boot-loader/ ./
COPY --from=builder --chown=banking:banking /extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=banking:banking /extracted/application/ ./

USER banking

EXPOSE 8080

# MaxRAMPercentage makes the heap track the container limit instead of the host's RAM,
# which is what makes the compose memory limits meaningful. No GC or JIT tuning beyond
# that: flags like TieredStopAtLevel=1 buy startup speed at the cost of steady-state
# throughput, which is the wrong trade for a service that is meant to stay up.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"

HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=5 \
    CMD wget -q -O - http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
