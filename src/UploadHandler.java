import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;

/**
 * POST /upload: stores a JSON document in the upload directory.
 *
 * The upload directory is separate from the web root, so uploaded files are
 * never served back as static files. Each upload gets a random UUID file name
 * and is created with CREATE_NEW, so it can never overwrite an existing file.
 */
public class UploadHandler {
    private final Path uploadDir;

    public UploadHandler(Path uploadDir) throws IOException {
        Files.createDirectories(uploadDir);
        this.uploadDir = uploadDir;
    }

    public int handle(HttpRequest request, OutputStream out, boolean keepAlive) throws IOException {
        String contentType = request.header("Content-Type");
        if (contentType == null || !contentType.trim().toLowerCase().startsWith("application/json")) {
            return error(out, 415, "Only application/json is accepted.", keepAlive);
        }

        // This is only a basic shape check (valid UTF-8 that looks like a JSON object),
        // not a full JSON parser.
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(request.body)).toString().trim();
        } catch (CharacterCodingException e) {
            return error(out, 400, "Body is not valid UTF-8.", keepAlive);
        }
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return error(out, 400, "Body must be a JSON object.", keepAlive);
        }

        String id = UUID.randomUUID().toString();
        String fileName = "upload_" + id + ".json";
        try {
            Files.write(uploadDir.resolve(fileName), request.body,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            System.err.printf("[%s] Could not save upload: %s%n", Server.getTimeStamp(), e.getMessage());
            return error(out, 500, "Could not save the file.", keepAlive);
        }

        String json = "{\"status\":\"success\",\"id\":\"" + id + "\",\"file\":\"" + fileName + "\"}";
        HttpResponseBuilder.sendBytes(out, 201, "application/json", json.getBytes(StandardCharsets.UTF_8),
            Map.of(), true, keepAlive);
        return 201;
    }

    private int error(OutputStream out, int status, String message, boolean keepAlive) throws IOException {
        HttpResponseBuilder.sendError(out, status, message, Map.of(), true, keepAlive);
        return status;
    }
}
