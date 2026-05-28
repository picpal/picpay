FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY services/billing/build/libs/*.jar app.jar
USER app
EXPOSE 8082
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
