import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads HTTP/1.x requests straight from the socket's byte stream.
 *
 * The request line and headers are read byte by byte until the empty line
 * (CRLF CRLF). Header bytes are decoded as ISO-8859-1, so one byte is always one
 * char. The body is then read as raw bytes: exactly Content-Length bytes.
 * Reading the body as bytes (not chars) is what keeps the stream
 * in sync for the next request on a keep-alive connection.
 *
 * Parsing is split into readHead() and readBody() so the connection handler can
 * use different timeouts for the two phases.
 */
public class HttpRequestParser {
    private final int maxHeaderBytes;
    private final int maxHeaderCount;
    private final long maxBodyBytes;

    // Bytes still allowed for the request line + headers of the current request
    private int headerBytesLeft;

    public HttpRequestParser(int maxHeaderBytes, int maxHeaderCount, long maxBodyBytes) {
        this.maxHeaderBytes = maxHeaderBytes;
        this.maxHeaderCount = maxHeaderCount;
        this.maxBodyBytes = maxBodyBytes;
    }

    /**
     * Reads the request line and headers.
     * Returns null if the client closed the connection before sending anything.
     */
    public HttpRequest readHead(InputStream in) throws IOException, HttpException {
        headerBytesLeft = maxHeaderBytes;

        // RFC 7230 section 3.5: ignore empty lines received before the request line
        String requestLine;
        do {
            requestLine = readLine(in, 414);
            if (requestLine == null) return null;
        } while (requestLine.isEmpty());

        HttpRequest request = parseRequestLine(requestLine);
        readHeaders(in, request);
        checkBodyFraming(request);
        return request;
    }

    /** Reads the body described by the headers (if any) into request.body. */
    public void readBody(InputStream in, HttpRequest request) throws IOException, HttpException {
        if (request.contentLength > 0) {
            byte[] body = in.readNBytes((int) request.contentLength);
            if (body.length < request.contentLength) {
                throw new EOFException("Client closed the connection in the middle of the body");
            }
            request.body = body;
        }
    }

    // ------------------------------------------------------------------ request line

    private HttpRequest parseRequestLine(String line) throws HttpException {
        // method SP request-target SP HTTP-version, with exactly one space between parts
        String[] parts = line.split(" ", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new HttpException(400, "Malformed request line");
        }
        String method = parts[0];
        String target = parts[1];
        String version = parts[2];

        if (!isToken(method)) {
            throw new HttpException(400, "Invalid method name");
        }
        if (!version.matches("HTTP/\\d\\.\\d")) {
            throw new HttpException(400, "Malformed HTTP version");
        }
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            throw new HttpException(505, "Only HTTP/1.0 and HTTP/1.1 are supported");
        }
        return new HttpRequest(method, toOriginForm(target), version);
    }

    /**
     * Most clients send "/path?query" (origin-form). Proxies may send the full URL
     * (absolute-form), which servers must also accept; we keep only the path part.
     */
    private String toOriginForm(String target) throws HttpException {
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c <= ' ' || c >= 0x7F) {
                throw new HttpException(400, "Invalid character in request target");
            }
        }
        if (target.startsWith("/")) {
            return target;
        }
        String lower = target.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            int pathStart = target.indexOf('/', lower.indexOf("://") + 3);
            return pathStart >= 0 ? target.substring(pathStart) : "/";
        }
        throw new HttpException(400, "Request target must start with '/'");
    }

    // ------------------------------------------------------------------ headers

    private void readHeaders(InputStream in, HttpRequest request) throws IOException, HttpException {
        int count = 0;
        while (true) {
            String line = readLine(in, 431);
            if (line == null) {
                throw new EOFException("Client closed the connection in the middle of the headers");
            }
            if (line.isEmpty()) {
                return; // end of headers
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new HttpException(400, "Folded header lines are not supported");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new HttpException(400, "Header line without a name");
            }
            String name = line.substring(0, colon);
            String value = line.substring(colon + 1).trim();
            // A space before the colon is not allowed (RFC 7230 section 3.2.4)
            if (!isToken(name)) {
                throw new HttpException(400, "Invalid header name");
            }
            count++;
            if (count > maxHeaderCount) {
                throw new HttpException(431, "Too many header fields");
            }
            if (name.equalsIgnoreCase("Host") && request.headers.containsKey("Host")) {
                throw new HttpException(400, "More than one Host header");
            }
            // Repeated headers are combined into one comma separated value
            request.headers.merge(name, value, (oldValue, newValue) -> oldValue + ", " + newValue);
        }
    }

    /** Works out how long the body is. */
    private void checkBodyFraming(HttpRequest request) throws HttpException {
        String transferEncoding = request.header("Transfer-Encoding");
        String contentLength = request.header("Content-Length");

        if (transferEncoding != null) {
            throw new HttpException(501, "Transfer-Encoding is not supported yet");
        }
        if (contentLength != null) {
            request.contentLength = parseContentLength(contentLength);
            if (request.contentLength > maxBodyBytes) {
                throw new HttpException(413, "Request body is larger than " + maxBodyBytes + " bytes");
            }
        }
    }

    /**
     * Content-Length must be a plain non-negative number. If the header was sent
     * more than once (we see "5, 5" after combining) every copy must agree.
     */
    static long parseContentLength(String value) throws HttpException {
        long result = -1;
        for (String part : value.split(",", -1)) {
            String digits = part.trim();
            if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new HttpException(400, "Invalid Content-Length: " + value);
            }
            if (digits.length() > 18) {
                throw new HttpException(413, "Content-Length is too large");
            }
            long length = Long.parseLong(digits);
            if (result != -1 && result != length) {
                throw new HttpException(400, "Conflicting Content-Length values");
            }
            result = length;
        }
        return result;
    }

    // ------------------------------------------------------------------ low level helpers

    /**
     * Reads one line ending in CRLF (a bare LF is also accepted) and returns it
     * without the line ending. Returns null if the stream ends before the first byte.
     * Throws HttpException(statusIfTooLong) if the header byte budget runs out.
     */
    private String readLine(InputStream in, int statusIfTooLong) throws IOException, HttpException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1) {
                if (line.length() == 0) return null;
                throw new EOFException("Connection closed in the middle of a line");
            }
            if (--headerBytesLeft < 0) {
                throw new HttpException(statusIfTooLong,
                    statusIfTooLong == 414 ? "Request line is too long" : "Request header fields are too large");
            }
            if (b == '\n') {
                return line.toString();
            }
            if (b == '\r') {
                int next = in.read();
                headerBytesLeft--;
                if (next != '\n') {
                    throw new HttpException(400, "Bare CR in request header");
                }
                return line.toString();
            }
            if ((b < 0x20 && b != '\t') || b == 0x7F) {
                throw new HttpException(400, "Control character in request header");
            }
            line.append((char) b); // ISO-8859-1: byte value == char value
        }
    }

    /** token = 1*tchar (RFC 7230 section 3.2.6) */
    private static boolean isToken(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) return false;
        }
        return true;
    }
}
