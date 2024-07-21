FROM adoptopenjdk/openjdk8:alpine-slim
WORKDIR /myplus
VOLUME /myplus
COPY target/myplus.war myplus.war
# ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","/myplus.jar"]

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "myplus.jar"]
# # RUN mvn clean install -DskipTests

# CMD mvn spring-boot:run