# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Copy all module POMs first — parent pom.xml references all three modules
COPY pom.xml .
COPY shared-contracts/pom.xml shared-contracts/
COPY consumer-worker/pom.xml consumer-worker/
COPY producer-service/pom.xml producer-service/

# Download dependencies (layer cached unless any pom.xml changes)
RUN mvn -pl shared-contracts,consumer-worker -am dependency:go-offline -q

# Copy source and build
COPY shared-contracts/src shared-contracts/src
COPY consumer-worker/src consumer-worker/src
RUN mvn -pl shared-contracts,consumer-worker -am package -DskipTests -q

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

RUN groupadd --system appgroup && useradd --system --gid appgroup --no-create-home appuser
USER appuser

COPY --from=builder /build/consumer-worker/target/consumer-worker-*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
