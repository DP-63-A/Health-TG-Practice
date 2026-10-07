FROM gradle:8.11.1-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :backend:bot:bootJar --no-daemon --console=plain

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /workspace/backend/bot/build/libs/*.jar app.jar
RUN mkdir -p /var/lib/health-tg/files && chown -R app:app /app /var/lib/health-tg
USER 10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
