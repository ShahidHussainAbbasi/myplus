FROM adoptopenjdk/openjdk8:alpine-slim
VOLUME /tmp
COPY target/myplus.war myplus.war
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","/myplus.war"]

WORKDIR /myplus
COPY . .
RUN mvn clean install

CMD mvn spring-boot:run