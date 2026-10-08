FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-jammy AS probe
WORKDIR /probe
COPY platform/images/HealthProbe.java .
RUN javac --release 21 HealthProbe.java
FROM --platform=linux/amd64 eclipse-temurin:21-jre-jammy AS java
FROM --platform=linux/amd64 jrottenberg/ffmpeg@sha256:292a972c60356abd651d9a4f9c808c13e7473f65ad400b7eb99215f4e571931d
RUN apt-get update && apt-get install --only-upgrade --no-install-recommends -y gpgv libssl3t64 && rm -rf /var/lib/apt/lists/*
COPY --from=java /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:/usr/local/bin:/usr/bin:/bin
WORKDIR /app
COPY --from=probe /probe/HealthProbe.class /app/probe/HealthProbe.class
COPY services/media-service/target/media-service-0.1.0-SNAPSHOT-exec.jar /app/app.jar
USER 10001:10001
EXPOSE 8091
HEALTHCHECK --interval=10s --timeout=4s --start-period=90s --retries=6 CMD ["java","-cp","/app/probe","HealthProbe","http://127.0.0.1:8091/health/ready"]
ENTRYPOINT ["/opt/java/openjdk/bin/java","-jar","/app/app.jar"]
CMD ["--spring.main.web-application-type=none","--ott.media.worker=true","--ott.media.worker-health.enabled=true"]
