# MyPlus monolith — Java 25, matches the microservice Dockerfiles. Build the jar first
# (mvn -DskipTests package -> target/myplus.jar); docker compose builds with this repo-root context.
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
VOLUME /tmp
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar
EXPOSE 8080
USER app
ENTRYPOINT ["java","-jar","/app.jar"]
