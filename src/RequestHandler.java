import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
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
            socket.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
            out = new BufferedOutputStream(socket.getOutputStream(), 8192);
            HttpRequestParser parser =
                new HttpRequestParser(config.maxHeaderBytes, config.maxHeaderCount, config.maxBodyBytes);

            int requestsHandled = 0;
            while (true) {
                HttpRequest request = parser.readHead(in);
                if (request == null) break;

                // RFC 7230 section 5.4: an HTTP/1.1 request without Host must get 400
                if (request.isHttp11() && request.header("Host") == null) {
                    throw new HttpException(400, "Missing Host header");
                }

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
        } catch (IOException e) {
            // Client went away, timed out or reset the connection; nothing useful to send
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
        } catch (IOException e) {
            // client is already gone
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
