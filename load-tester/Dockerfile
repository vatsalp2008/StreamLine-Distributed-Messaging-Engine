# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S bench && adduser -S bench -G bench
COPY --from=build --chown=bench:bench /build/target/*-jar-with-dependencies.jar bench.jar
USER bench

# Target and load shape come from STREAMLINE_* environment variables.
ENTRYPOINT ["java", "-jar", "bench.jar"]
