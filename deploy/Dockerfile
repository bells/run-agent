FROM eclipse-temurin:21-jre-jammy@sha256:dff6eb46527bd293a3c06bbbe9e64e8c9f91f01905b5abbdad31761c03059d2c

WORKDIR /app
COPY --chmod=0444 run-agent.jar /app/run-agent.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/run-agent.jar"]
