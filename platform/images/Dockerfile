FROM eclipse-temurin:21-jdk-jammy AS probe
WORKDIR /probe
COPY platform/images/HealthProbe.java .
RUN javac --release 21 HealthProbe.java
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install --only-upgrade --no-install-recommends -y libssl3 && rm -rf /var/lib/apt/lists/*
ARG OWNER
WORKDIR /app
COPY --from=probe /probe/HealthProbe.class /app/probe/HealthProbe.class
COPY services/${OWNER}-service/target/${OWNER}-service-0.1.0-SNAPSHOT-exec.jar /app/app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError" SERVER_PORT=8080
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=4s --start-period=60s --retries=6 CMD ["java","-cp","/app/probe","HealthProbe","http://127.0.0.1:8080/actuator/health/readiness"]
ENTRYPOINT ["java","-jar","/app/app.jar"]
