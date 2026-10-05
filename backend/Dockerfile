# Build the self-contained jar.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
RUN ./gradlew --no-daemon buildFatJar

# Run it on a JRE alone, which keeps the image small.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/banca-backend-all.jar app.jar

# Free hosting gives the container 512 MB, so leave the JVM some headroom.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# The host says which port to listen on through PORT; 8080 is the fallback.
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
