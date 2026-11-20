FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B package
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/habit-tracker-1.0.0.jar app.jar
ENV JAVA_TOOL_OPTIONS="-Xmx256m -XX:ActiveProcessorCount=2"
USER 10001
ENTRYPOINT ["java","-jar","app.jar"]
