FROM maven:3.9-eclipse-temurin-25-alpine AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q clean package -DskipTests

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S careos && adduser -S careos -G careos
WORKDIR /app
COPY --from=build /workspace/target/careos-backend-*.jar app.jar
USER careos
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
