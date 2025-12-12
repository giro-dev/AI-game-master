FROM eclipse-temurin:21-jdk-jammy as build
WORKDIR /workspace
COPY . /workspace
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /workspace/build/libs/master-server-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

