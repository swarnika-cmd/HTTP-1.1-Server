import java.nio.file.Path;
import java.util.Map;

/**
 * All the tunable settings of the server in one place.
 *
 * Defaults are set here, then overridden by environment variables, then by
 * command line flags (--name=value). The tests create a ServerConfig directly
 * and change the fields they care about, e.g. very short timeouts.
 */
public class ServerConfig {

    // Network
    String host = "127.0.0.1";
    int port = 8080;
    int backlog = 128;

    // "platform" = bounded pool of platform threads, "virtual" = one virtual thread per connection
    String mode = "platform";
    int threads = 64;
    int queueCapacity = 128;

    // Files
    Path webRoot = Path.of("resources");
    Path uploadDir = Path.of("uploads");

    // Timeouts (milliseconds)
    int idleTimeoutMs = 5_000;      // how long a keep-alive connection may sit idle between requests
    int headerTimeoutMs = 10_000;   // total time allowed to receive the request line and headers
    int bodyReadTimeoutMs = 10_000; // max wait for each read while receiving a body
    int lingerMs = 500;             // how long to drain input before closing after an error

    // Limits
    int maxRequestsPerConnection = 100;
    int maxHeaderBytes = 8 * 1024;
    int maxHeaderCount = 100;
    long maxBodyBytes = 10L * 1024 * 1024;

    // Logging
    boolean logRequests = false;
    int statsIntervalSeconds = 30; // 0 disables the periodic pool status line
    boolean quiet = false;         // tests turn off the startup/shutdown messages

    boolean isVirtualMode() {
        return "virtual".equals(mode);
    }

    /** Builds a config from environment variables and command line flags (flags win). */
    static ServerConfig load(Map<String, String> env, String[] args) {
        ServerConfig config = new ServerConfig();

        // Cloud hosts (Render, Railway, Heroku) pass the port in $PORT and expect
        // the app to listen on all interfaces.
        if (env.containsKey("PORT")) {
            config.port = parseInt("PORT", env.get("PORT"));
            config.host = "0.0.0.0";
        }
        if (env.containsKey("HOST")) config.host = env.get("HOST");
        if (env.containsKey("SERVER_MODE")) config.mode = env.get("SERVER_MODE");
        if (env.containsKey("THREADS")) config.threads = parseInt("THREADS", env.get("THREADS"));
        if (env.containsKey("QUEUE_CAPACITY")) config.queueCapacity = parseInt("QUEUE_CAPACITY", env.get("QUEUE_CAPACITY"));
        if (env.containsKey("WEB_ROOT")) config.webRoot = Path.of(env.get("WEB_ROOT"));
        if (env.containsKey("UPLOAD_DIR")) config.uploadDir = Path.of(env.get("UPLOAD_DIR"));
        if (env.containsKey("LOG_REQUESTS")) config.logRequests = Boolean.parseBoolean(env.get("LOG_REQUESTS"));

        for (String arg : args) {
            if (arg.equals("--log-requests")) {
                config.logRequests = true;
                continue;
            }
            int eq = arg.indexOf('=');
            if (!arg.startsWith("--") || eq < 0) {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
            String name = arg.substring(2, eq);
            String value = arg.substring(eq + 1);
            switch (name) {
                case "host" -> config.host = value;
                case "port" -> config.port = parseInt(name, value);
                case "mode" -> config.mode = value;
                case "threads" -> config.threads = parseInt(name, value);
                case "queue" -> config.queueCapacity = parseInt(name, value);
                case "web-root" -> config.webRoot = Path.of(value);
                case "upload-dir" -> config.uploadDir = Path.of(value);
                case "idle-timeout-ms" -> config.idleTimeoutMs = parseInt(name, value);
                case "header-timeout-ms" -> config.headerTimeoutMs = parseInt(name, value);
                case "max-requests" -> config.maxRequestsPerConnection = parseInt(name, value);
                case "max-body-bytes" -> config.maxBodyBytes = Long.parseLong(value);
                default -> throw new IllegalArgumentException("Unknown option: --" + name);
            }
        }

        config.validate();
        return config;
    }

    void validate() {
        if (!mode.equals("platform") && !mode.equals("virtual")) {
            throw new IllegalArgumentException("mode must be 'platform' or 'virtual', got: " + mode);
        }
        if (threads < 1) throw new IllegalArgumentException("threads must be >= 1");
        if (queueCapacity < 1) throw new IllegalArgumentException("queue capacity must be >= 1");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("invalid port: " + port);
        if (idleTimeoutMs < 1 || headerTimeoutMs < 1 || bodyReadTimeoutMs < 1) {
            throw new IllegalArgumentException("timeouts must be at least 1 ms");
        }
    }

    private static int parseInt(String name, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number, got: " + value);
        }
    }

    static String usage() {
        return """
            Usage: java -cp out Server [options]
              --host=ADDR             address to bind (default 127.0.0.1, env HOST)
              --port=N                port to listen on (default 8080, env PORT)
              --mode=platform|virtual thread model (default platform, env SERVER_MODE)
              --threads=N             worker threads in platform mode (default 64, env THREADS)
              --queue=N               connections waiting for a worker (default 128, env QUEUE_CAPACITY)
              --web-root=DIR          static files directory (default resources, env WEB_ROOT)
              --upload-dir=DIR        where POST /upload stores files (default uploads, env UPLOAD_DIR)
              --idle-timeout-ms=N     keep-alive idle timeout (default 5000)
              --header-timeout-ms=N   time allowed to send all headers (default 10000)
              --max-requests=N        requests per connection before closing (default 100)
              --max-body-bytes=N      largest accepted request body (default 10485760)
              --log-requests          print one line per request (env LOG_REQUESTS=true)
            """;
    }
}
