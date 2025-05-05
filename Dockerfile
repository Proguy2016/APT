FROM maven:3.9-amazoncorretto-17 AS build

WORKDIR /app

# Copy pom.xml and source code
COPY pom.xml .
COPY src ./src

# Build the application with the shade plugin
RUN mvn clean package -DskipTests

FROM openjdk:17-slim

WORKDIR /app

# Copy the executable JAR file from the build stage
COPY --from=build /app/target/collaborative-editor-1.0-SNAPSHOT.jar app.jar

# Create a simple init script that Railway expects
RUN echo '#!/bin/bash\nexec java -jar /app/app.jar "$@"' > /usr/local/bin/docker-entrypoint.sh \
    && chmod +x /usr/local/bin/docker-entrypoint.sh

# Expose the port the server runs on
EXPOSE 8887

# Set the entry point to the script
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"] 