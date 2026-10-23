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
RUN chmod +x ./mvnw && ./mvnw dependency:go-offline -B

# Copy application source and build production artifact
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# Extract layered components using Spring Boot 3.3 tools jarmode
# Ensures dependencies, loader, snapshots, and application layers exist
RUN java -Djarmode=tools -jar target/api-rate-limiter-*.jar extract --layers --launcher --destination extracted && \
    mkdir -p extracted/dependencies extracted/spring-boot-loader extracted/snapshot-dependencies extracted/application

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
COPY --from=builder --chown=appuser:appgroup /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/extracted/application/ ./

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
