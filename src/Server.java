import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sets up the listening socket and the worker threads.
 *
 * One "acceptor" thread accepts connections and hands each one to the
 * executor (a fixed size thread pool).
 */
public class Server {
    private final ServerConfig config;
    private final Set<Socket> openConnections = ConcurrentHashMap.newKeySet();

    private ServerSocket serverSocket;
    private ExecutorService workers;
    private Thread acceptThread;
    private StaticFileHandler staticFiles;
    private UploadHandler uploads;
    private volatile boolean running;

    public Server(ServerConfig config) {
        this.config = config;
    }

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--help")) {
            System.out.print(ServerConfig.usage());
            return;
        }
        ServerConfig config;
        try {
            config = ServerConfig.load(System.getenv(), args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.print(ServerConfig.usage());
            System.exit(2);
            return;
        }

        Server server = new Server(config);
        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Could not start server: " + e.getMessage());
            System.exit(1);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.awaitStop();
    }

    /** Binds the socket and starts accepting connections in the background. */
    public void start() throws IOException {
        staticFiles = new StaticFileHandler(config.webRoot);
        uploads = new UploadHandler(config.uploadDir);
        serverSocket = new ServerSocket(config.port, config.backlog, InetAddress.getByName(config.host));
        workers = createExecutor();
        running = true;

        acceptThread = new Thread(this::acceptLoop, "acceptor");
        acceptThread.start();

        log("HTTP server listening on http://%s:%d", config.host, getPort());
        log("Thread pool: %d workers, queue capacity %d", config.threads, config.queueCapacity);
        if (config.statsIntervalSeconds > 0) startStatsThread();
        log("Serving files from '%s', uploads go to '%s'", config.webRoot, config.uploadDir);
    }

    private ExecutorService createExecutor() {
        return Executors.newFixedThreadPool(config.threads, namedThreadFactory("worker-"));
    }

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (!running) break;
                System.err.printf("[%s] Error accepting connection: %s%n", getTimeStamp(), e.getMessage());
                continue;
            }

            openConnections.add(client);
            workers.execute(new RequestHandler(client, config, staticFiles, uploads, openConnections));
        }
    }

    /** Stops accepting, closes every open connection and stops the workers. */
    public void stop() {
        if (!running) return;
        running = false;
        try {
            serverSocket.close();
        } catch (IOException e) {
            // ignore
        }
        workers.shutdownNow();
        for (Socket socket : openConnections) {
            try {
                socket.close();
            } catch (IOException e) {
                // ignore
            }
        }
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
            acceptThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log("Server stopped.");
    }

    private void awaitStop() {
        try {
            acceptThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /** Prints the pool status every few seconds so saturation is visible in the logs. */
    private void startStatsThread() {
        ThreadPoolExecutor pool = (ThreadPoolExecutor) workers;
        Thread stats = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(config.statsIntervalSeconds * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                log("Pool: %d/%d busy, %d queued",
                    pool.getActiveCount(), pool.getPoolSize(), pool.getQueue().size());
            }
        }, "pool-stats");
        stats.setDaemon(true);
        stats.start();
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> new Thread(runnable, prefix + counter.incrementAndGet());
    }

    private void log(String format, Object... args) {
        if (config.quiet) return;
        System.out.printf("[%s] %s%n", getTimeStamp(), String.format(format, args));
    }

    public static String getTimeStamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}
