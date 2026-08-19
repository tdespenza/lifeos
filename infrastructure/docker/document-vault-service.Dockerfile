# The CI build creates the executable jar before this image build. The runtime image contains no
# source tree, Gradle cache, storage credentials, or document bytes.
FROM eclipse-temurin:25.0.3_9-jre-ubi10-minimal@sha256:266719156e679994ddeede6140e5d6f51211368657a182a07de7380a0c382fed

RUN groupadd --gid 10001 lifeos \
    && useradd --uid 10001 --gid lifeos --home-dir /nonexistent --no-create-home --shell /bin/false lifeos \
    && mkdir -p /var/lib/lifeos/document-vault-objects \
    && chown -R lifeos:lifeos /var/lib/lifeos

WORKDIR /app
ARG JAR_FILE
COPY --chown=lifeos:lifeos ${JAR_FILE} /app/app.jar

USER 10001:10001
EXPOSE 8088 9088
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
