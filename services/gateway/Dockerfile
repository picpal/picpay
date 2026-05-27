# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace
COPY build.gradle settings.gradle gradle.properties gradlew ./
COPY gradle ./gradle
RUN chmod +x gradlew
# Copy only build scripts first — allows Gradle dependency download layer to be cached
COPY common/build.gradle ./common/
COPY services/gateway/build.gradle ./services/gateway/
COPY services/payment/build.gradle ./services/payment/
COPY services/billing/build.gradle ./services/billing/
COPY services/token/build.gradle ./services/token/
COPY services/notification/build.gradle ./services/notification/
# Pre-warm Gradle dependency cache (downloads deps without source)
RUN ./gradlew :services:gateway:dependencies --no-daemon 2>/dev/null || true
# Now copy actual source and build
COPY common/src ./common/src
COPY services/gateway/src ./services/gateway/src
RUN ./gradlew :services:gateway:bootJar -x test --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=builder /workspace/services/gateway/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
