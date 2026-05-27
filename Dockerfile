# Use an official eclipse-temurin JDK 17 as the base image
FROM eclipse-temurin:17-jdk-jammy

# Set working directory inside the container
WORKDIR /app

# Copy the source and resource directories
COPY src/ /app/src/
COPY resources/ /app/resources/

# Compile the Java application into the out/ directory
RUN mkdir -p out && javac -d out src/**/*.java

# Expose port 8080 to the host machine
EXPOSE 8080

# Run the Server class
# Args: port (8080), host (0.0.0.0 for container access), threadPoolSize (10)
CMD ["java", "-cp", "out", "Server", "8080", "0.0.0.0", "10"]
