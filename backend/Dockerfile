FROM maven:3.9-eclipse-temurin-25-alpine@sha256:d4517045882c5e7459afbfab27ada9c682a0da6f15e96c4bb52a8597d5e3c2b8 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q clean package -DskipTests

FROM busybox:1.37.0-uclibc@sha256:8d7b1636e974e0adfd8d945955fca609304f0a56c18799dfd032d6e661382d84 AS healthcheck

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:ca60da1345c0f17b6d019049e6749e15f10fd3c0da86dec938d2b4ec565d0629
WORKDIR /app
COPY --from=healthcheck /bin/busybox /busybox
COPY --from=build --chown=65532:65532 /workspace/target/careos-backend-*.jar app.jar
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
