import java.net.*;
import java.io.IOException;
import java.util.concurrent.*;

// this classs will handle the configuration, thread pool setup and socket binding
public class Server {
    // Configuration constants (private static final)
    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_POOL_SIZE = 10;
    private static final int BACKLOG = 50;
    public static final String RESOURCES_DIR = "resources";
    public static final String SERVER_NAME = "Multi-threaded HTTP Server";

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String host = DEFAULT_HOST;
        int poolSize = DEFAULT_POOL_SIZE;

        try {
            // 1. Parse Command Line Arguments
            if (args.length > 0) port = Integer.parseInt(args[0]);
            if (args.length > 1) host = args[1];
            if (args.length > 2) poolSize = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid argument for port or thread pool size. Using defaults.");
        }

        // 2. Thread Pool Initialization (Requirement 3)
        // Uses the standard Java thread pool for safe, automatic queueing and reuse.
        ExecutorService threadPool = Executors.newFixedThreadPool(poolSize);

        // 3. Socket Binding and Listening (Requirement 2)
        try (ServerSocket serverSocket = new ServerSocket(
                port, BACKLOG, InetAddress.getByName(host))) {
            
            String serverAddress = serverSocket.getInetAddress().getHostAddress();
            int serverPort = serverSocket.getLocalPort();
            String hostValidationTarget = serverAddress + ":" + serverPort;

            // Startup Logging (Requirement 10)
            System.out.printf("[%s] HTTP Server started on http://%s:%d%n", 
                getTimeStamp(), serverAddress, serverPort);
            System.out.printf("[%s] Thread pool size: %d%n", getTimeStamp(), poolSize);
            System.out.printf("[%s] Serving files from '%s' directory%n", getTimeStamp(), RESOURCES_DIR);
            System.out.printf("[%s] Press Ctrl+C to stop the server%n", getTimeStamp());
            
            // 4. Main Loop: Listen for connections
            while (true) {
                try {
                    // Blocks until a client connects
                    Socket clientSocket = serverSocket.accept(); 
                    
                    // 5. Submit task to the thread pool
                    threadPool.submit(new RequestHandler(clientSocket, hostValidationTarget, threadPool));

                } catch (RejectedExecutionException e) {
                    // Thread pool saturated (Req 3, 9: 503)
                    // In a simple fixed thread pool, this usually means the server is shutting down.
                    // For a Service Unavailable (503), we'd typically need a custom queue
                    // to manage the backlog and actively reject or wait. 
                    System.err.printf("[%s] Warning: Thread pool saturated. Connection rejected.%n", getTimeStamp());
                } catch (IOException e) {
                    if (serverSocket.isClosed()) break; // Exit if server intentionally closed
                    System.err.println("Error accepting connection: " + e.getMessage());
                }
            }

        } catch (IOException e) {
            System.err.println("Could not start server: " + e.getMessage());
        } finally {
            threadPool.shutdownNow(); // Clean shutdown of all threads
            System.out.printf("[%s] Server stopped.%n", getTimeStamp());
        }
    }
    
    // Helper for logging timestamp
    public static String getTimeStamp() {
        return java.time.LocalTime.now().toString();
    }
}