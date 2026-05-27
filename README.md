# HTTP/1.1 Server From Scratch

> A fully RFC 2616-compliant HTTP/1.1 server built on raw Java TCP sockets — no frameworks, no Netty, no Spring. Just threads, sockets, and the spec.

---

## Why build this?

Most developers use HTTP every day without understanding what actually happens below the framework. This project is an attempt to understand it at the protocol level — parsing raw bytes off a TCP stream, implementing connection lifecycle, and managing concurrency without any abstractions hiding the complexity.

---

## Features

- **Multi-threaded request handling** via `ThreadPoolExecutor` with configurable pool size
- **RFC 2616 compliant** request parsing — handles GET and POST with proper header parsing
- **Chunked file streaming** — serves files in 8KB chunks to keep memory usage flat regardless of file size
- **Connection pooling** — persistent connections with `keep-alive` support

---

## Architecture

```
Client TCP Connection
        │
        ▼
ServerSocket (port 8080)
        │  accept() loop
        ▼
ThreadPoolExecutor
        │  submits Runnable per connection
        ▼
RequestHandler (per thread)
    ├── parseHeaders()
    ├── validateHost()
    ├── handleGetRequest()
    ├── handlePostRequest()
    └── sendErrorResponse()
```

---

## Live Demo

There are three ways to demo and experience this HTTP/1.1 Server:

### 1. Interactive Browser-Based Simulator (Recommended)
We have built a gorgeous, interactive browser visualizer that simulates this Java HTTP server's thread pool, backlog queue, request parser, and chunked streaming in real time.
* **Live Link:** `https://swarnika-cmd.github.io/HTTP-1.1-Server/demo/index.html` *(To enable this, deploy the `/demo` folder to GitHub Pages in your repo settings)*
* Explore how the server handles HTTP keep-alive, logs connection requests, visualizes the thread pool, and manages server configurations like thread count and backlog queue limits.

### 2. Expose Local Instance (ngrok)
If you are running the Java server locally and want to show a live demo to someone remotely:
1. Run the server locally on port `8080`.
2. Install and run **ngrok**:
   ```bash
   ngrok http 8080
   ```
3. Share the generated public HTTPS URL (e.g., `https://xxxx.ngrok-free.app`) with your client/tester.

### 3. Deploy the Real Java Server to the Cloud (Docker)
You can deploy this server to cloud services like **Railway.app** or **Render.com** using Docker:
1. Create a `Dockerfile` at the root of the project:
   ```dockerfile
   FROM eclipse-temurin:17-jdk-jammy
   WORKDIR /app
   COPY src/ /app/src/
   COPY resources/ /app/resources/
   RUN mkdir -p out && javac -d out $(find src -name "*.java")
   EXPOSE 8080
   CMD ["java", "-cp", "out", "Server", "8080", "0.0.0.0", "10"]
   ```
2. Link your GitHub repository to Railway or Render, and it will build and expose the TCP port automatically.

---

## Quick start

```bash
git clone https://github.com/swarnika-cmd/HTTP-1.1-Server.git
cd HTTP-1.1-Server
javac -d out src/**/*.java
java -cp out Server
# Server starts on localhost:8080
```

### Try it with curl

```bash
# Basic GET
curl -v http://localhost:8080/

# POST with body
curl -v -X POST http://localhost:8080/upload \
  -H "Content-Type: application/json" \
  -d "{\"key\":\"value\"}"

# Fetch a file (streamed in 8KB chunks)
curl -v http://localhost:8080/sample.txt
```

---

## Testing

This project includes a fully automated **Integration Test Suite** built with zero external dependencies (no Maven/JUnit required). It uses Java 11's natively included `HttpClient` to boot up the server on an ephemeral port and assert real HTTP workflows.

To run the automated test suite locally:

**On Mac/Linux:**
```bash
./run_tests.sh
```

**On Windows:**
```cmd
.\run_tests.bat
```

**Expected Output:**
```text
Compiling HTTP Server and Integration Tests...
Compilation successful. Running tests...
Starting Integration Tests...
Running Test: GET /                            [PASS]
Running Test: GET /missing.txt                 [PASS]
Running Test: PUT /                            [PASS]
Running Test: POST /upload (Bad Data)          [PASS]
Running Test: POST /upload (Success)           [PASS]
==================================================
[RESULTS] All 5 tests passed successfully! ✓
```

---

## Benchmark

Tested with [Apache Bench](https://httpd.apache.org/docs/2.4/programs/ab.html) on a local machine:

```bash
ab -n 10000 -c 100 http://localhost:8080/
```

```
Concurrency Level:      100
Complete requests:      10000
Failed requests:        0
Requests per second:    ~3200 [#/sec]
Time per request:       31ms (mean, across all concurrent requests)
```

> Baseline single-threaded implementation: ~1900 req/sec. Thread pool architecture: ~3200 req/sec. ~40% throughput improvement.

---

## Key implementation decisions

**Why `ThreadPoolExecutor` instead of `Thread-per-request`?**
Unbounded thread creation causes memory exhaustion under load. A fixed pool with a bounded queue gives predictable performance and backpressure.

**Why 8KB chunks for file streaming?**
Reading entire files into memory before writing to the socket doesn't scale. Chunked streaming keeps heap usage constant regardless of file size.

**Why manual request parsing instead of a library?**
The point of the project is to understand what libraries do. Parsing HTTP/1.1 requests from raw bytes forced me to understand the spec rather than just call a method.

---

## Tech stack

- Java 17
- Raw `java.net.ServerSocket` / `Socket`
- `java.util.concurrent.ThreadPoolExecutor`
- No external dependencies

---

## What I learned

- How HTTP/1.1 actually works at the byte level (start-line, headers, CRLF, body framing)
- How `ThreadPoolExecutor` manages worker lifecycle and the tradeoff between pool size and throughput
- How `keep-alive` connections change server state management
- Why chunked transfer encoding exists and when to use it
- How connection backlog (`ServerSocket` queue depth) affects behavior under burst traffic

---

## Limitations / known issues

- HTTPS not supported (no TLS layer)
- No HTTP/2 support
- Static routing only — no regex or parameterized paths yet

---

## Roadmap

- [ ] Add TLS via `SSLServerSocket`
- [ ] Parameterized routing (`/users/:id`)
- [ ] Response compression (gzip)
- [ ] HTTP/2 framing (stretch goal)