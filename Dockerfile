# Build stage: compile with the full JDK
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src/ src/
RUN javac -d out src/*.java

# Runtime stage: only the JRE, the compiled classes and the static files
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out/ out/
COPY resources/ resources/
RUN mkdir -p uploads

# Listen on all interfaces inside the container (the default 127.0.0.1 would be unreachable)
ENV HOST=0.0.0.0
EXPOSE 8080

# Options can be appended to `docker run`, e.g. --mode=virtual or --threads=32
ENTRYPOINT ["java", "-cp", "out", "Server"]
