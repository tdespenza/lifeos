# The CI build creates the executable jar before this image build. Keeping the runtime image
# separate avoids shipping Gradle, source files, dependency caches, or deployment secrets.
# Pin the multi-architecture Temurin UBI Minimal release rather than a mutable base-image tag.
FROM eclipse-temurin:25.0.3_9-jre-ubi10-minimal@sha256:266719156e679994ddeede6140e5d6f51211368657a182a07de7380a0c382fed

RUN groupadd --gid 10001 lifeos \
    && useradd --uid 10001 --gid lifeos --home-dir /nonexistent --no-create-home --shell /bin/false lifeos

WORKDIR /app
ARG JAR_FILE
COPY --chown=lifeos:lifeos ${JAR_FILE} /app/app.jar

USER 10001:10001
EXPOSE 8084 9084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
