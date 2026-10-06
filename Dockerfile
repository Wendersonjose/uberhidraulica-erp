# syntax=docker/dockerfile:1
# Build reprodutível: nenhuma dependência do host além do Docker.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine AS runtime
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS=""
# Segue a porta em que o Spring realmente escuta (PORT do provedor, senão SERVER_PORT, senão 8080).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD sh -c 'wget -q -O /dev/null "http://127.0.0.1:${PORT:-${SERVER_PORT:-8080}}/actuator/health" || exit 1'
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
