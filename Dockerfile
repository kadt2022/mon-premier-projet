FROM eclipse-temurin:17-jdk AS build

WORKDIR /workspace

COPY gradle gradle
COPY gradlew .
COPY settings.gradle .
COPY build.gradle .
COPY src src

RUN sed -i 's/\r$//' gradlew \
    && chmod +x gradlew \
    && ./gradlew clean jar --no-daemon

FROM eclipse-temurin:17-jre

WORKDIR /app

ENV AUTH_MODE=both \
    SFTP_PORT=2222 \
    WEB_PORT=8080 \
    SFTP_ROOT=/data \
    PROTECT_LOCAL_STORAGE=true \
    ENABLE_WEB_UI=true

COPY --from=build /workspace/build/libs/*.jar /app/sftp-server.jar

VOLUME ["/data"]

EXPOSE 2222 8080

ENTRYPOINT ["java", "-jar", "/app/sftp-server.jar"]
