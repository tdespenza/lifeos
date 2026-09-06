# Gradle supplies the executable jar; no source, caches, or credentials enter the runtime image.
FROM eclipse-temurin:25.0.3_9-jre-ubi10-minimal@sha256:266719156e679994ddeede6140e5d6f51211368657a182a07de7380a0c382fed

RUN groupadd --gid 10001 lifeos \
    && useradd --uid 10001 --gid lifeos --home-dir /nonexistent --no-create-home --shell /bin/false lifeos

WORKDIR /app
ARG JAR_FILE
COPY --chown=lifeos:lifeos ${JAR_FILE} /app/app.jar

USER 10001:10001
EXPOSE 8087 9087
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
