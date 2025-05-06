FROM maven:3.9-amazoncorretto-17 AS build

WORKDIR /app

# Copy pom.xml first to optimize build cache
COPY pom.xml .

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# Runtime image
FROM amazoncorretto:17

WORKDIR /app

# Copy the JAR from the build stage
COPY --from=build /app/target/collaborative-editor-1.0-SNAPSHOT.jar /app/app.jar

# Set environment variables
ENV PORT=27017
ENV HOST=0.0.0.0

# Expose the port for WebSocket communication
EXPOSE 27017

# Run the application
CMD ["java", "-Xms128m", "-Xmx512m", "-jar", "/app/app.jar"] 