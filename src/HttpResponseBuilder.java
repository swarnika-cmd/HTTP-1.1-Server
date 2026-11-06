import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Writes HTTP responses. Every response gets a status line, Date, Server,
 * Connection and a Content-Length that counts bytes (not chars).
 */
public class HttpResponseBuilder {
    public static final String SERVER_NAME = "JavaHttpServer/2.0";

    // IMF-fixdate from RFC 7231 section 7.1.1.1, e.g. "Sun, 06 Nov 1994 08:49:37 GMT".
    // (DateTimeFormatter.RFC_1123_DATE_TIME prints single digit days as "6", which is not allowed.)
    private static final DateTimeFormatter HTTP_DATE =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    private static final int FILE_BUFFER_SIZE = 8192;

    public static String httpDate() {
        return HTTP_DATE.format(ZonedDateTime.now(ZoneOffset.UTC));
    }

    public static String reasonPhrase(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 413 -> "Payload Too Large";
            case 414 -> "URI Too Long";
            case 415 -> "Unsupported Media Type";
            case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 503 -> "Service Unavailable";
            case 505 -> "HTTP Version Not Supported";
            default -> "Unknown";
        };
    }

    /** Sends a response whose body is already in memory. */
    public static void sendBytes(OutputStream out, int status, String contentType, byte[] body,
                                 Map<String, String> extraHeaders, boolean includeBody, boolean keepAlive)
            throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("Content-Length", String.valueOf(body.length));
        headers.putAll(extraHeaders);
        writeHead(out, status, headers, keepAlive);
        if (includeBody) {
            out.write(body);
        }
        out.flush();
    }

    /** Sends a small HTML error page. */
    public static void sendError(OutputStream out, int status, String message, Map<String, String> extraHeaders,
                                 boolean includeBody, boolean keepAlive) throws IOException {
        String html = "<html><head><title>" + status + " " + reasonPhrase(status) + "</title></head><body>"
            + "<h1>" + status + " " + reasonPhrase(status) + "</h1>"
            + "<p>" + escapeHtml(message) + "</p></body></html>";
        sendBytes(out, status, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8),
            extraHeaders, includeBody, keepAlive);
    }

    /**
     * Streams a file in 8 KB pieces so large files never need to fit in memory.
     * For HEAD requests (includeBody = false) only the headers are sent.
     */
    public static void sendFile(OutputStream out, Path file, String contentType, Map<String, String> extraHeaders,
                                boolean includeBody, boolean keepAlive) throws IOException {
        long size = Files.size(file);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("Content-Length", String.valueOf(size));
        headers.putAll(extraHeaders);
        writeHead(out, 200, headers, keepAlive);

        if (includeBody) {
            try (InputStream fileIn = Files.newInputStream(file)) {
                byte[] buffer = new byte[FILE_BUFFER_SIZE];
                long remaining = size; // never send more than we promised in Content-Length
                while (remaining > 0) {
                    int n = fileIn.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (n == -1) {
                        throw new IOException("File got shorter while sending: " + file);
                    }
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
            }
        }
        out.flush();
    }

    /** Writes the status line and headers, plus the headers every response needs. */
    private static void writeHead(OutputStream out, int status, Map<String, String> headers, boolean keepAlive)
            throws IOException {
        StringBuilder head = new StringBuilder();
        // We always answer as HTTP/1.1; that is allowed for HTTP/1.0 clients too (RFC 7230 section 2.6)
        head.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n");
        head.append("Date: ").append(httpDate()).append("\r\n");
        head.append("Server: ").append(SERVER_NAME).append("\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
