FROM eclipse-temurin:21-jre-jammy@sha256:dff6eb46527bd293a3c06bbbe9e64e8c9f91f01905b5abbdad31761c03059d2c

WORKDIR /app
ARG APP_JAR=run-agent.jar
COPY --chmod=0444 ${APP_JAR} /app/run-agent.jar
RUN install -d -m 0700 -o 10001 -g 10001 /var/lib/run-agent/reviews /var/lib/run-agent/marathon-podiums /var/lib/run-agent/conversations
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/run-agent.jar"]
