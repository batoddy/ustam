# Multi-stage build: compiles with Gradle inside the container (nothing installed
# on the developer's machine), then ships only the runtime JRE + fat jar.
FROM gradle:8.10.2-jdk17 AS build
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . .
RUN gradle buildFatJar --no-daemon

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /home/gradle/project/build/libs/*-all.jar app.jar

# Render provides $PORT at runtime; the app reads it in Application.kt.
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
