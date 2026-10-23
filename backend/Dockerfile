FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
# `package` runs the unit tests, so a failing test stops the image build.
# Integration tests (*IT, Testcontainers) run in CI with `mvn verify`.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system cloudpulse && useradd --system --gid cloudpulse cloudpulse
COPY --from=build /workspace/target/cloudpulse-api-*.jar app.jar
USER cloudpulse
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
