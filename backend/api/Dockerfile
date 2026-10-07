FROM gradle:8.11.1-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :backend:api:bootJar --no-daemon --console=plain

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /workspace/backend/api/build/libs/*.jar app.jar
RUN mkdir -p /var/lib/health-tg/files && chown -R app:app /app /var/lib/health-tg
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
