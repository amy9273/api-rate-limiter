# ==============================================================================
# Multi-Stage Production Dockerfile — High-Throughput API Rate Limiter
# Java 21 LTS | Hardened Alpine Runtime | Non-Root User | Layered JAR Cache
# ==============================================================================

# Stage 1: Build & Layer Extraction
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy Maven wrapper and POM for dependency resolution
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B

# Copy application source and build production artifact
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

WORKDIR /workspace/target
RUN java -Djarmode=tools -jar *.jar extract || java -Djarmode=layertools -jar *.jar extract

# ------------------------------------------------------------------------------
# Stage 2: Minimal Production Runtime
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runner
WORKDIR /app

# Security Hardening: Create unprivileged system group and user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Install curl/wget for container health check probes
RUN apk add --no-cache curl wget

# Copy extracted layers in order of change frequency (infrequent -> frequent)
COPY --from=builder --chown=appuser:appgroup /workspace/target/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/target/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/target/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/target/application/ ./

# Switch to non-root user
USER appuser:appgroup

# Container metadata and port
EXPOSE 8080

# Production Health Check Probe
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Container Ergonomics: Optimal memory percentage, thread stack, and urandom entropy
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "org.springframework.boot.loader.launch.JarLauncher"]
