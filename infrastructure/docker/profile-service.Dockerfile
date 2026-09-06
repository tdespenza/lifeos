# The executable jar is produced by Gradle before the image build. This keeps source code,
# Gradle caches, and build credentials out of the runtime image. Pin the hardened UBI 10 runtime
# digest rather than inheriting a mutable tag.
FROM eclipse-temurin:25.0.3_9-jre-ubi10-minimal@sha256:266719156e679994ddeede6140e5d6f51211368657a182a07de7380a0c382fed

RUN groupadd --gid 10001 lifeos \
    && useradd --uid 10001 --gid lifeos --home-dir /nonexistent --no-create-home --shell /bin/false lifeos

WORKDIR /app
ARG JAR_FILE
COPY --chown=lifeos:lifeos ${JAR_FILE} /app/app.jar

USER 10001:10001
EXPOSE 8083 9083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
