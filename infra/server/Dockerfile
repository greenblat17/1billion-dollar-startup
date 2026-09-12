FROM eclipse-temurin:21-jdk AS build
WORKDIR /cmp
COPY cmp/ .
RUN chmod +x gradlew \
    && ./gradlew :server:buildFatJar --no-daemon -Dorg.gradle.java.installations.auto-download=false

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /cmp/server/build/libs/server-all.jar /app/server-all.jar
EXPOSE 8080
ENV SERVER_PORT=8080
ENTRYPOINT ["java", "-jar", "/app/server-all.jar"]
