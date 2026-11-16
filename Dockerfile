# Apex Commerce backend (Quarkus, Java 21)
# --- build ---
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

# --- run ---
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S apex && adduser -S apex -G apex && mkdir -p /data/uploads && chown -R apex:apex /data
WORKDIR /app
COPY --from=build --chown=apex:apex /src/target/quarkus-app/ ./
USER apex
ENV APP_UPLOAD_DIR=/data/uploads \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
VOLUME /data/uploads
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s CMD wget -qO- http://localhost:8080/q/health/ready >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar quarkus-run.jar"]
