# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Copy all module POMs first — parent pom.xml references all three modules
# so Maven requires all pom.xml files to exist before resolving dependencies
COPY pom.xml .
COPY shared-contracts/pom.xml shared-contracts/
COPY producer-service/pom.xml producer-service/
COPY consumer-worker/pom.xml consumer-worker/

# Download dependencies (layer cached unless any pom.xml changes)
RUN mvn -pl shared-contracts,producer-service -am dependency:go-offline -q

# Copy source and build
COPY shared-contracts/src shared-contracts/src
COPY producer-service/src producer-service/src
RUN mvn -pl shared-contracts,producer-service -am package -DskipTests -q

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

RUN groupadd --system appgroup && useradd --system --gid appgroup --no-create-home appuser
USER appuser

COPY --from=builder /build/producer-service/target/producer-service-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
