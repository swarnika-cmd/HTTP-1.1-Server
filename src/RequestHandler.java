import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Set;

/**
 * Handles one client connection from start to finish. A keep-alive connection
 * can carry many requests, so this loops: read request, send response, repeat,
 * until the client or one of our limits closes the connection.
 */
public class RequestHandler implements Runnable {
    // Methods defined by RFC 7231 (+ PATCH). Known but unsupported ones get 405, others 501.
    private static final Set<String> KNOWN_METHODS =
        Set.of("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH");

    private final Socket socket;
    private final ServerConfig config;
    private final StaticFileHandler staticFiles;
    private final UploadHandler uploads;
    private final Set<Socket> openConnections;

    public RequestHandler(Socket socket, ServerConfig config, StaticFileHandler staticFiles,
                          UploadHandler uploads, Set<Socket> openConnections) {
        this.socket = socket;
        this.config = config;
        this.staticFiles = staticFiles;
        this.uploads = uploads;
        this.openConnections = openConnections;
    }

    @Override
    public void run() {
        OutputStream out = null;
        try {
            TimedInputStream timedIn = new TimedInputStream(socket);
            InputStream in = new BufferedInputStream(timedIn, 8192);
            out = new BufferedOutputStream(socket.getOutputStream(), 8192);
            HttpRequestParser parser =
                new HttpRequestParser(config.maxHeaderBytes, config.maxHeaderCount, config.maxBodyBytes);

            int requestsHandled = 0;
            while (true) {
                // 1. Wait for the first byte of the next request. An idle keep-alive
                //    connection is closed quietly after idleTimeoutMs.
                timedIn.setReadTimeout(config.idleTimeoutMs);
                in.mark(1);
                try {
                    if (in.read() == -1) break; // client closed the connection
                } catch (SocketTimeoutException e) {
                    break;
                }
                in.reset();

                // 2. The request line and all headers must arrive within headerTimeoutMs,
                //    no matter how slowly the bytes trickle in.
                timedIn.startDeadline(config.headerTimeoutMs);
                HttpRequest request = parser.readHead(in);
                if (request == null) break;

                // RFC 7230 section 5.4: an HTTP/1.1 request without Host must get 400
                if (request.isHttp11() && request.header("Host") == null) {
                    throw new HttpException(400, "Missing Host header");
                }

                // 3. The body only has a per-read timeout.
                timedIn.setReadTimeout(config.bodyReadTimeoutMs);
                parser.readBody(in, request);

                requestsHandled++;
                boolean keepAlive = request.wantsKeepAlive()
                    && requestsHandled < config.maxRequestsPerConnection;

                int status = route(request, out, keepAlive);
                if (config.logRequests) {
                    System.out.printf("[%s] [%s] %s %s %s -> %d%n", Server.getTimeStamp(),
                        Thread.currentThread().getName(), request.method, request.target, request.version, status);
                }
                if (!keepAlive) break;
            }
        } catch (HttpException e) {
            // The request was invalid. We do not know where the next request would
            // start, so send the error and close the connection.
            sendErrorAndClose(out, e.getStatusCode(), e.getMessage());
        } catch (SocketTimeoutException e) {
            // The client started a request but was too slow to finish it
            sendErrorAndClose(out, 408, "Request took too long to arrive.");
        } catch (IOException e) {
            // Client went away or reset the connection; nothing useful to send
        } finally {
            closeSocket();
        }
    }

    /** Picks the handler for the request and returns the response status. */
    private int route(HttpRequest request, OutputStream out, boolean keepAlive) throws IOException {
        String method = request.method;
        boolean isUploadPath = request.rawPath().equals("/upload");

        if (isUploadPath && method.equals("POST")) {
            return uploads.handle(request, out, keepAlive);
        }
        if (!isUploadPath && (method.equals("GET") || method.equals("HEAD"))) {
            return staticFiles.handle(request, out, keepAlive);
        }

        boolean includeBody = !method.equals("HEAD");
        if (KNOWN_METHODS.contains(method)) {
            // 405 must say which methods this resource does support (RFC 7231 section 6.5.5)
            String allowed = isUploadPath ? "POST" : "GET, HEAD";
            HttpResponseBuilder.sendError(out, 405, method + " is not allowed here.",
                Map.of("Allow", allowed), includeBody, keepAlive);
            return 405;
        }
        HttpResponseBuilder.sendError(out, 501, "Unknown method " + method + ".", Map.of(), includeBody, keepAlive);
        return 501;
    }

    private void sendErrorAndClose(OutputStream out, int status, String message) {
        if (out == null) return;
        try {
            HttpResponseBuilder.sendError(out, status, message, Map.of(), true, false);
            lingeringClose();
        } catch (IOException e) {
            // client is already gone
        }
    }

    /**
     * After an error the client may still be sending (e.g. the rest of a huge
     * body). If we close while unread data is waiting, the OS sends a TCP reset
     * and the client can lose our error response. So we first shut down our
     * sending side, then read and throw away input for a short time.
     */
    private void lingeringClose() throws IOException {
        socket.shutdownOutput();
        socket.setSoTimeout(config.lingerMs);
        InputStream raw = socket.getInputStream();
        byte[] discard = new byte[8192];
        long end = System.nanoTime() + config.lingerMs * 1_000_000L;
        try {
            while (System.nanoTime() < end && raw.read(discard) != -1) {
                // keep draining
            }
        } catch (SocketTimeoutException e) {
            // done waiting
        }
    }

    private void closeSocket() {
        openConnections.remove(socket);
        try {
            socket.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
