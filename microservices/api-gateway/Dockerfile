FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
VOLUME /tmp
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar
USER app
ENTRYPOINT ["java","-jar","/app.jar"]
