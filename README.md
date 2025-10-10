Multi-threaded HTTP Server Implementation (Java Sockets)
For this project, I built a simple HTTP/1.1 server from scratch using Java sockets. The main goals were to get hands-on experience with low-level TCP socket programming and to manage concurrency properly with a thread pool. I also focused on following some core HTTP specs like handling GET/POST requests, persistent connections, and proper headers.

🚀 Getting Started
What You Need
Java Development Kit (JDK) 17 or higher

Make sure your project folder looks like this: [Project Root]/{src, resources/{uploads, index.html, ...}}

How to Build and Run
Go to your project’s root directory:

bash
cd C:\Users\somva\first program\ComputerNetworks\Project
Compile the Java files (I used UTF-8 encoding to avoid weird symbol errors):

bash
javac -encoding UTF-8 src/Server.java src/RequestHandler.java
Run the server! You can pass up to three arguments: [Port] [Host Address] [Max Thread Pool Size].

Here are the defaults and some examples:

Argument	Default	Example
Port	8080	8000
Host Address	127.0.0.1	0.0.0.0
Max Threads	10	20
Example command line:

bash
java -cp src Server 8000 0.0.0.0 20
(Press Ctrl+C to stop the server when you want.)

🛠️ What I Added / Improved
I added a monitor thread that logs the thread pool status every 30 seconds to keep track of how busy the server is.

The server now prints timestamps on logs for all major events like startup, connections, queue warnings, and shutdown.

If the server gets too busy and the thread pool queue is full (over 50 connections waiting), it sends a 503 Service Unavailable response instead of crashing or blocking.

I included a graceful shutdown hook so the server cleans up resources properly when stopped.

You can customize the port, host address, and max threads easily before starting the server.

Overloaded requests get a helpful 503 error with a Retry-After timeout to avoid spamming the server.

💡 How It Works
Thread Pool Handling
I decided to use Java's ThreadPoolExecutor to manage concurrency because it lets me monitor and control the threads better. This way, my server limits active connections to a safe max and queues up excess requests.

Transferring Files
The server streams files (like images and text) in chunks of 8KB instead of loading whole files into memory. This helps with performance and prevents memory issues.

Connection Management
Connections stay alive by default unless the client says otherwise. There’s a 30-second timeout on idle sockets, and I limit clients to 100 requests per connection to keep things fair.

🔒 Security Stuff
I made sure to block path traversal attacks by checking requested file paths carefully.

The server rejects requests that don’t have the right Host header matching the running server address, so no sneaky cross-domain requests sneak through.

⚠️ Things I Know Could Be Better
JSON validation is basic, just checking for simple structure, not full schema validation.

Error pages are simple HTML, nothing fancy for now.

📋 Summary of Changes
Thread pool monitoring and logging with timestamps.

Clean server shutdown handling.

Smarter rejection of extra connections with 503 responses.

Clearer log messages for queue status and incoming connections.