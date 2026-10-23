FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
# Unit tests run as part of `package` and gate the image.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system demo && useradd --system --gid demo demo
COPY --from=build /workspace/target/demo-service-*.jar app.jar
USER demo
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
