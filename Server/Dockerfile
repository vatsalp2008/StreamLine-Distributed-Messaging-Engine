# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# resolve dependencies first so source edits do not invalidate the dependency layer
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# run unprivileged; the H2 file lives on a volume owned by this user
RUN addgroup -S streamline && adduser -S streamline -G streamline \
    && mkdir -p /app/data && chown -R streamline:streamline /app

COPY --from=build --chown=streamline:streamline /build/target/*.jar app.jar

USER streamline
EXPOSE 8080
VOLUME ["/app/data"]

# container memory, not host memory, decides the heap
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# /ready rather than /health: liveness stays green with a dead database, so it
# would keep an instance that cannot persist anything in the load balancer.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8080/ready || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
