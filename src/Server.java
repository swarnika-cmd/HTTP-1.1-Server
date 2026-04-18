import java.net.*;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.*;
import java.text.SimpleDateFormat;
import java.util.Date;

// This class handles the configuration, thread pool setup and socket binding
public class Server {
    // Configuration constants (private static final)
    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_POOL_SIZE = 10;
    private static final int BACKLOG = 50;
    private static final int MAX_QUEUE_SIZE = 50; // Maximum queued connections before 503
    public static final String RESOURCES_DIR = "resources";
    public static final String SERVER_NAME = "Multi-threaded HTTP Server";

    // Thread pool and server state
    private static volatile boolean serverRunning = true;
    private static ThreadPoolExecutor threadPool;

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
        // Uses ThreadPoolExecutor for better monitoring and control
        threadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(poolSize);

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
            
            // 4. Start Thread Pool Monitoring Thread (Requirement 10)
            startMonitoringThread();
            
            // 5. Add Graceful Shutdown Hook
            addShutdownHook(serverSocket);
            
            // 6. Main Loop: Listen for connections
            while (serverRunning) {
                try {
                    // Blocks until a client connects
                    Socket clientSocket = serverSocket.accept();
                    
                    String timestamp = getTimeStamp();
                    String clientAddress = clientSocket.getInetAddress().getHostAddress() + 
                                         ":" + clientSocket.getPort();
                    
                    // Check if thread pool is severely saturated (503 handling)
                    long queueSize = threadPool.getQueue().size();
                    if (queueSize > MAX_QUEUE_SIZE) {
                        System.out.printf("[%s] Thread pool exhausted, rejecting connection from %s%n", 
                            timestamp, clientAddress);
                        send503Response(clientSocket);
                        clientSocket.close();
                        continue;
                    }
                    
                    // Log connection
                    System.out.printf("[%s] Connection from %s%n", timestamp, clientAddress);
                    
                    // Check if queueing is happening (Queue logging)
                    int activeThreads = threadPool.getActiveCount();
                    int maxThreads = threadPool.getMaximumPoolSize();
                    
                    if (queueSize > 0 || activeThreads >= maxThreads) {
                        System.out.printf("[%s] Warning: Thread pool saturated, queuing connection (Queue size: %d)%n", 
                            timestamp, queueSize + 1);
                    }
                    
                    // Submit task to the thread pool
                    threadPool.submit(new RequestHandler(clientSocket, hostValidationTarget, threadPool));
                    
                    // Log when connection is dequeued
                    if (queueSize > 0) {
                        System.out.printf("[%s] Connection dequeued, assigned to available thread%n", 
                            getTimeStamp());
                    }

                } catch (RejectedExecutionException e) {
                    // Thread pool rejected the task (shutdown or custom policy)
                    System.err.printf("[%s] Warning: Thread pool rejected connection.%n", getTimeStamp());
                } catch (IOException e) {
                    if (serverSocket.isClosed() || !serverRunning) break;
                    System.err.println("Error accepting connection: " + e.getMessage());
                }
            }

        } catch (IOException e) {
            System.err.println("Could not start server: " + e.getMessage());
        } finally {
            shutdownThreadPool();
        }
    }
    
    /**
     * This starts a daemon thread to monitor thread pool status every 30 seconds
     * Requirement 10: Thread Pool Status Logging
     */
    private static void startMonitoringThread() {
        Thread monitoringThread = new Thread(() -> {
            while (serverRunning) {
                try {
                    Thread.sleep(30000); // Monitor every 30 seconds
                    if (serverRunning) {
                        int activeCount = threadPool.getActiveCount();
                        int poolSize = threadPool.getPoolSize();
                        long queueSize = threadPool.getQueue().size();
                        
                        System.out.printf("[%s] Thread pool status: %d/%d active, %d queued%n", 
                            getTimeStamp(), activeCount, poolSize, queueSize);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        monitoringThread.setDaemon(true);
        monitoringThread.setName("ThreadPoolMonitor");
        monitoringThread.start();
    }
    
    /**
     *  this adds a shutdown hook for graceful server termination
     */
    private static void addShutdownHook(ServerSocket serverSocket) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            serverRunning = false;
            System.out.printf("[%s] Shutting down server...%n", getTimeStamp());
            
            try {
                serverSocket.close();
            } catch (IOException e) {
                // Ignore
            }
            
            shutdownThreadPool();
        }));
    }
    
    /**
     * Gracefully shuts down the thread pool
     */
    private static void shutdownThreadPool() {
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                System.out.printf("[%s] Thread pool did not terminate gracefully, forcing shutdown%n", 
                    getTimeStamp());
                threadPool.shutdownNow();
                
                if (!threadPool.awaitTermination(2, TimeUnit.SECONDS)) {
                    System.err.println("Thread pool did not terminate");
                }
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.printf("[%s] Server stopped.%n", getTimeStamp());
    }
    
    /**
     * Sends a 503 Service Unavailable response to overloaded clients
     * Requirement 9: Error Responses with Retry-After header
     */
    private static void send503Response(Socket clientSocket) {
        try {
            OutputStream out = clientSocket.getOutputStream();
            String timestamp = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z").format(new Date());
            
            String htmlBody = "<html><head><title>503 Service Unavailable</title></head>" +
                             "<body><h1>503 Service Unavailable</h1>" +
                             "<p>The server is currently overloaded. Please try again in 10 seconds.</p>" +
                             "</body></html>";
            
            String response = "HTTP/1.1 503 Service Unavailable\r\n" +
                             "Content-Type: text/html; charset=utf-8\r\n" +
                             "Content-Length: " + htmlBody.getBytes("UTF-8").length + "\r\n" +
                             "Connection: close\r\n" +
                             "Retry-After: 10\r\n" +
                             "Date: " + timestamp + "\r\n" +
                             "Server: " + SERVER_NAME + "\r\n" +
                             "\r\n" +
                             htmlBody;
            
            out.write(response.getBytes("UTF-8"));
            out.flush();
        } catch (IOException e) {
            // Silent failure - client connection may have already closed
        }
    }
    
    /**
     * Helper method for logging timestamp in ISO format
     * Returns timestamp in format: yyyy-MM-dd HH:mm:ss
     */
    public static String getTimeStamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}