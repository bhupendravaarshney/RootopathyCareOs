FROM maven:3-eclipse-temurin-26-alpine@sha256:706e9b698c40340c4849b2092e24d97a1290dea21106fbda9e6803530123a5b0 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q clean package -DskipTests

FROM busybox:1.37.0-uclibc@sha256:8d7b1636e974e0adfd8d945955fca609304f0a56c18799dfd032d6e661382d84 AS healthcheck

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:fce4a1d66284e8866c46113d9bdc286c46fb8c3c3f0a098f877034349e88debe
WORKDIR /app
COPY --from=healthcheck /bin/busybox /busybox
COPY --from=build --chown=65532:65532 /workspace/target/careos-backend-*.jar app.jar
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
