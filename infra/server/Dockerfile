FROM azul/zulu-openjdk:21 AS build
WORKDIR /cmp
COPY cmp/gradlew .
COPY cmp/gradle/wrapper gradle/wrapper
COPY cmp/gradle/gradle-daemon-jvm.properties gradle/gradle-daemon-jvm.properties
RUN chmod +x gradlew && ./gradlew --version

COPY cmp/ .
RUN ./gradlew :server:buildFatJar --no-daemon

FROM azul/zulu-openjdk:21-jre
WORKDIR /app
COPY --from=build /cmp/server/build/libs/server-all.jar /app/server-all.jar
EXPOSE 8080
ENV SERVER_PORT=8080
ENTRYPOINT ["java", "-jar", "/app/server-all.jar"]
