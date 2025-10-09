Multi-threaded HTTP Server Implementation (Java Sockets)
This project implements a simplified HTTP/1.1 server from scratch, focusing on low-level TCP socket programming, concurrency management using a thread pool, and adherence to core HTTP protocol specifications (GET/POST, persistent connections, headers, etc.).

🚀 Getting Started
Prerequisites
Java Development Kit (JDK) 17+

The project directory structure must be maintained as follows: [Project Root]/{src, resources/{uploads, index.html, ...}}.

Build and Run Instructions
Navigate to the project root directory:

Bash

cd C:\Users\somva\first program\ComputerNetworks\Project
Compile the source code (using UTF-8 encoding to avoid symbol errors):

Bash

javac -encoding UTF-8 src/Server.java src/RequestHandler.java
Run the server: The server accepts up to three command-line arguments: [Port] [Host Address] [Max Thread Pool Size].

Argument	Default Value	Example
Port	8080	8000
Host Address	127.0.0.1	0.0.0.0
Max Threads	10	20
Example Run Command:

Bash

java -cp src Server 8000 0.0.0.0 20
(Press Ctrl+C to stop the server.)

🛠️ Implementation Deep Dive
Thread Pool Architecture (Concurrency)
To handle multiple clients simultaneously, I opted to use the standard Java library's ExecutorService (Executors.newFixedThreadPool).

Fixed Pool Size: This approach ensures the server strictly limits resource usage to the configured maximum thread count (default: 10), preventing the machine from being overwhelmed by a high number of requests.

Automatic Queueing: When all worker threads are busy, the ExecutorService automatically places new client connections into an internal, thread-safe queue. This satisfies the requirement for connections to wait when the pool is saturated, without the need to manually implement locking mechanisms (like mutexes or condition variables) on the queue itself.

The Worker: Each client connection is handled by a RequestHandler task, which is assigned to an available thread.

Binary Transfer Implementation
Handling images (.png, .jpg) and text files (.txt) required careful use of Java's low-level I/O to ensure data integrity.

Binary Mode: All supported file types are read using a FileInputStream and transferred as a raw byte stream, which is crucial for preventing data corruption in binary files.

Chunked Transfer: Data is streamed in 8KB chunks (byte[] buffer = new byte[8192]) rather than reading the whole file into memory, improving performance and reducing memory overhead, especially for large image files.

Download Trigger: The response headers explicitly include Content-Type: application/octet-stream and the Content-Disposition: attachment header to force the browser to download the file instead of attempting to render the raw binary data inline.

Connection Management (Keep-Alive)
The server supports persistent HTTP/1.1 connections:

The connection defaults to keep-alive unless the client explicitly sends Connection: close.

A loop inside the RequestHandler keeps the socket open to process subsequent requests from the same client.

Timeout: The socket is set with a 30-second timeout (clientSocket.setSoTimeout(30000)). If no data is received within this time, the connection is closed (Requirement 8).

Limit: Connections are closed after 100 requests to prevent resource monopolization.

🔒 Security Measures
Security was a primary concern in the request handling logic:

Path Traversal Protection: This was the most critical security step. Before accessing any file, the requested path is canonicalized using File.getCanonicalPath() and compared against the canonical path of the resources directory. Any attempt to access files outside this directory (e.g., using ../etc/passwd) immediately triggers a 403 Forbidden response.

Host Header Validation: All requests must include a Host header that matches the server's running address (e.g., 127.0.0.1:8080). Missing or mismatched headers result in 400 Bad Request or 403 Forbidden, preventing requests meant for other domains from being processed.

⚠️ Known Limitations
JSON Validation: The server performs only a basic check for JSON structure (starts with { and ends with }). Full, robust JSON schema validation would require integrating a dedicated JSON parsing library.

Error Handling: The current implementation uses simple HTML for error pages. A production-ready server would use standardized, configurable error templates.