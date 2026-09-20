# Build stage: compiles and runs the test suite, so a broken build never produces an image.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first: this layer is cached unless pom.xml itself changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Tests are deliberately skipped here. ConcurrentHoldTest needs a Docker daemon
# (Testcontainers), which is not available inside an image build. CI runs the full
# `mvn verify` separately, so nothing goes untested.
RUN mvn -B clean package -DskipTests

# Runtime stage: JRE only, no Maven, no sources.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Running as a non-root user is the cheapest container hardening there is.
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
