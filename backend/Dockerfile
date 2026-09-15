FROM maven:3.9-eclipse-temurin-25-alpine@sha256:d4517045882c5e7459afbfab27ada9c682a0da6f15e96c4bb52a8597d5e3c2b8 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q clean package -DskipTests

FROM busybox:1.38.0-uclibc@sha256:297dda192bda2157ddf40abb47a45a1090caff1864db9cfb9ce4b901ba318a3c AS healthcheck

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:fce4a1d66284e8866c46113d9bdc286c46fb8c3c3f0a098f877034349e88debe
WORKDIR /app
COPY --from=healthcheck /bin/busybox /busybox
COPY --from=build --chown=65532:65532 /workspace/target/careos-backend-*.jar app.jar
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
