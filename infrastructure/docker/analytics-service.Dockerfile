FROM eclipse-temurin:25.0.3_9-jre-ubi10-minimal@sha256:266719156e679994ddeede6140e5d6f51211368657a182a07de7380a0c382fed

ARG JAR_FILE
WORKDIR /app
RUN groupadd --gid 10001 lifeos && useradd --uid 10001 --gid lifeos --home-dir /nonexistent --no-create-home --shell /bin/false lifeos
COPY --chown=lifeos:lifeos ${JAR_FILE} /app/app.jar
USER lifeos:lifeos
EXPOSE 8091 9091
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
