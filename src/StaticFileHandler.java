import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/** Serves files from the web root for GET and HEAD requests. */
public class StaticFileHandler {
    private final Path webRoot;

    public StaticFileHandler(Path webRoot) throws IOException {
        // Resolve the real path once (no "..", no symlinks) so the checks below compare like with like
        this.webRoot = webRoot.toRealPath();
    }

    /** Sends the response and returns the status code (used for logging). */
    public int handle(HttpRequest request, OutputStream out, boolean keepAlive) throws IOException {
        boolean includeBody = !request.method.equals("HEAD");

        String path;
        try {
            path = percentDecode(request.rawPath());
        } catch (IllegalArgumentException e) {
            return error(out, 400, "Invalid percent-encoding in path.", includeBody, keepAlive);
        }
        if (path.equals("/")) {
            path = "/index.html";
        }

        Path file;
        try {
            file = webRoot.resolve(path.substring(1)).normalize();
        } catch (InvalidPathException e) {
            return error(out, 400, "Invalid path.", includeBody, keepAlive);
        }

        // Path traversal check: after removing "." and ".." the file must still be inside the web root.
        // This also covers encoded forms like /%2e%2e/ because we check after decoding.
        if (!file.startsWith(webRoot)) {
            return error(out, 403, "Access denied.", includeBody, keepAlive);
        }
        if (!Files.isRegularFile(file)) {
            return error(out, 404, "The requested resource was not found.", includeBody, keepAlive);
        }
        // Check again with symlinks resolved, so a link inside the web root cannot point outside it
        Path realFile = file.toRealPath();
        if (!realFile.startsWith(webRoot)) {
            return error(out, 403, "Access denied.", includeBody, keepAlive);
        }

        String fileName = realFile.getFileName().toString();
        if (!MimeTypeResolver.isSupportedType(fileName)) {
            return error(out, 403, "This file type is not served.", includeBody, keepAlive);
        }

        Map<String, String> extraHeaders = MimeTypeResolver.isBinaryDownload(fileName)
            ? Map.of("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
            : Map.of();
        HttpResponseBuilder.sendFile(out, realFile, MimeTypeResolver.getContentType(fileName),
            extraHeaders, includeBody, keepAlive);
        return 200;
    }

    private int error(OutputStream out, int status, String message, boolean includeBody, boolean keepAlive)
            throws IOException {
        HttpResponseBuilder.sendError(out, status, message, Map.of(), includeBody, keepAlive);
        return status;
    }

    /**
     * Decodes %XX escapes in a URL path, e.g. "/my%20file.html" -> "/my file.html".
     * Unlike URLDecoder this leaves '+' alone, because '+' only means space in
     * form data, not in paths. The decoded bytes must be valid UTF-8.
     */
    static String percentDecode(String path) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '%') {
                if (i + 2 >= path.length()) {
                    throw new IllegalArgumentException("Incomplete percent escape");
                }
                int high = Character.digit(path.charAt(i + 1), 16);
                int low = Character.digit(path.charAt(i + 2), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Bad percent escape");
                }
                bytes.write(high * 16 + low);
                i += 2;
            } else {
                bytes.write(c); // the parser already made sure the target is plain ASCII
            }
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            if (decoded.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("NUL byte in path");
            }
            return decoded;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Path is not valid UTF-8");
        }
    }
}
