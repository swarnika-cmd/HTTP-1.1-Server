import java.net.Socket;
import java.net.SocketTimeoutException;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

public class RequestHandler implements Runnable {
    private final Socket clientSocket;
    private final String hostValidationTarget;
    private final ExecutorService threadPool;
    private int requestsHandled = 0;
    private static final int MAX_REQUESTS = 100;
    private static final int TIMEOUT_SECONDS = 30;
    private static final boolean DEBUG = false;

    public RequestHandler(Socket clientSocket, String hostValidationTarget, ExecutorService threadPool) {
        this.clientSocket = clientSocket;
        this.hostValidationTarget = hostValidationTarget;
        this.threadPool = threadPool;
    }

    @Override
    public void run() {
        long threadId = Thread.currentThread().getId();
        String clientInfo = String.format("%s:%d", clientSocket.getInetAddress().getHostAddress(), clientSocket.getPort());

        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            OutputStream out = clientSocket.getOutputStream();
        ) {
            clientSocket.setSoTimeout(TIMEOUT_SECONDS * 1000); // 30 seconds timeout (Req 8)
            System.out.printf("[%s] [Thread-%d] Connection from %s%n", Server.getTimeStamp(), threadId, clientInfo);

            // Loop for persistent connections (Keep-Alive)
            while (requestsHandled < MAX_REQUESTS) {
                
                // 1. Read Request Line
                String requestLine = in.readLine();
                if (requestLine == null || requestLine.isEmpty()) break; // Client closed connection or timeout

                // 2. Parse Request Line
                String[] parts = requestLine.split(" ");
                if (parts.length != 3) {
                    HttpResponseBuilder.sendErrorResponse(out, "HTTP/1.1", 400, "Bad Request", "Malformed request line.", false, threadId);
                    break;
                }
                String method = parts[0];
                String path = parts[1];
                String httpVersion = parts[2];
                
                System.out.printf("[%s] [Thread-%d] Request: %s %s %s%n", Server.getTimeStamp(), threadId, method, path, httpVersion);

                // 3. Parse Headers
                Map<String, String> headers = parseHeaders(in);
                String hostHeader = headers.getOrDefault("Host", null);
                String connectionHeader = headers.getOrDefault("Connection", "keep-alive");
                long contentLength = Long.parseLong(headers.getOrDefault("Content-Length", "0"));
                
                // 4. Security Check: Host Validation (Requirement 7)
                if (!validateHost(hostHeader, out, httpVersion)) break;

                // 5. Method Handling (Requirement 4, 5, 6)
                if ("GET".equals(method)) {
                    handleGetRequest(path, out, httpVersion, connectionHeader);
                } else if ("POST".equals(method)) {
                    // Read request body
                    int bufferSize = (int) Math.min(contentLength, 8192);
                    char[] bodyBuffer = new char[bufferSize]; 
                    StringBuilder requestBodyBuilder = new StringBuilder();
                    int totalRead = 0;
                    int toRead = (int) contentLength;
                    while (totalRead < toRead) {
                        int charsRead = in.read(bodyBuffer, 0, Math.min(bodyBuffer.length, toRead - totalRead));
                        if (charsRead == -1) break; // End of stream reached prematurely
                        requestBodyBuilder.append(bodyBuffer, 0, charsRead);
                        totalRead += charsRead;
                    }
                    String requestBody = requestBodyBuilder.toString();
                    handlePostRequest(path, headers, requestBody, out, httpVersion, connectionHeader);
                } else {
                    // 405 Method Not Allowed
                    HttpResponseBuilder.sendErrorResponse(out, httpVersion, 405, "Method Not Allowed", "Server only supports GET and POST.", false, threadId);
                }

                requestsHandled++;
                
                // 6. Connection Management (Requirement 8)
                boolean keepAlive = ("keep-alive".equalsIgnoreCase(connectionHeader) || "HTTP/1.1".equalsIgnoreCase(httpVersion)) && 
                                    (!"close".equalsIgnoreCase(connectionHeader)) && requestsHandled < MAX_REQUESTS;
                
                if (!keepAlive) break; 
                
                // Log connection status
                System.out.printf("[%s] [Thread-%d] Connection: %s%n", Server.getTimeStamp(), threadId, keepAlive ? "keep-alive" : "close");
            }

        } catch (SocketTimeoutException e) {
            System.out.printf("[%s] [Thread-%d] Connection timeout (%d seconds).%n", Server.getTimeStamp(), threadId, TIMEOUT_SECONDS);
        } catch (IOException e) {
            // Usually connection reset by client or other I/O errors
            System.err.printf("[%s] [Thread-%d] I/O error on connection %s: %s%n", Server.getTimeStamp(), threadId, clientInfo, e.getMessage());
        } finally {
            // Close socket (Requirement 2)
            try {
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                }
                System.out.printf("[%s] [Thread-%d] Connection closed for %s. Requests: %d%n", 
                    Server.getTimeStamp(), threadId, clientInfo, requestsHandled);
            } catch (IOException e) { /* Ignore errors on close */ }
        }
    }
    
    // =========================================================================
    //                            Helper Methods
    // =========================================================================

    private Map<String, String> parseHeaders(BufferedReader in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String headerLine;
        while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) {
            int colonIndex = headerLine.indexOf(':');
            if (colonIndex > 0) {
                String key = headerLine.substring(0, colonIndex).trim();
                String value = headerLine.substring(colonIndex + 1).trim();
                headers.put(key, value);
            }
        }
        return headers;
    }

    private boolean validateHost(String hostHeader, OutputStream out, String httpVersion) throws IOException {
        long threadId = Thread.currentThread().getId();
        if (hostHeader == null) {
            System.out.printf("[%s] [Thread-%d] Host validation: Missing Host header ❌%n", Server.getTimeStamp(), threadId);
            HttpResponseBuilder.sendErrorResponse(out, httpVersion, 400, "Bad Request", "Missing Host header.", false, threadId);
            return false;
        }
        if (!hostHeader.equals(hostValidationTarget)) {
            // NOTE: A more flexible server would check host name against all bound interfaces
            // For this assignment, strict match is required.
            System.out.printf("[%s] [Thread-%d] Host validation: Host mismatch (%s vs %s) ❌%n", 
                Server.getTimeStamp(), threadId, hostHeader, hostValidationTarget);
            HttpResponseBuilder.sendErrorResponse(out, httpVersion, 403, "Forbidden", "Host header does not match server address.", false, threadId);
            return false;
        }
        System.out.printf("[%s] [Thread-%d] Host validation: %s ✓%n", Server.getTimeStamp(), threadId, hostHeader);
        return true;
    }

    // =============================== GET Handlers ===============================

    private void handleGetRequest(String path, OutputStream out, String httpVersion, String connectionHeader) throws IOException {
        long threadId = Thread.currentThread().getId();
        
        if ("/".equals(path)) path = "/index.html";

        if (DEBUG) {
            System.out.printf("[%s] [Thread-%d] DEBUG: Original path: %s%n", Server.getTimeStamp(), threadId, path);
            System.out.printf("[%s] [Thread-%d] DEBUG: Resources dir: %s%n", Server.getTimeStamp(), threadId, Server.RESOURCES_DIR);
        }

        // 1. Path Traversal Protection (Requirement 7)
        File requestedFile = new File(Server.RESOURCES_DIR + File.separator + path.substring(1));


        try {
            String canonicalPath = requestedFile.getCanonicalPath();
            String baseDir = new File(Server.RESOURCES_DIR).getCanonicalPath();

            if (!canonicalPath.startsWith(baseDir)) {
                System.out.printf("[%s] [Thread-%d] Security Violation: Path traversal blocked: %s%n", Server.getTimeStamp(), threadId, path);
                HttpResponseBuilder.sendErrorResponse(out, httpVersion, 403, "Forbidden", "Path traversal detected.", false, threadId);
                return;
            }
        } catch (IOException e) {
            HttpResponseBuilder.sendErrorResponse(out, httpVersion, 500, "Internal Server Error", "Could not process file path.", false, threadId);
            return;
        }

        // 2. File Existence Check (Requirement 9)
        if (!requestedFile.exists() || requestedFile.isDirectory()) {
            HttpResponseBuilder.sendErrorResponse(out, httpVersion, 404, "Not Found", "The requested resource was not found.", true, threadId);
            return;
        }

        String fileName = requestedFile.getName();
        if (!MimeTypeResolver.isSupportedType(fileName)) {
            HttpResponseBuilder.sendErrorResponse(out, httpVersion, 415, "Unsupported Media Type", "File type not supported.", true, threadId);
            return;
        }
        
        // 3. Send Response using HttpResponseBuilder
        HttpResponseBuilder.sendFileResponse(out, httpVersion, requestedFile, connectionHeader, threadId);

        if (DEBUG) {
            System.out.printf("[%s] [Thread-%d] DEBUG: Requested file path: %s%n", Server.getTimeStamp(), threadId, requestedFile.getPath());
            System.out.printf("[%s] [Thread-%d] DEBUG: File exists: %s%n", Server.getTimeStamp(), threadId, requestedFile.exists());
            System.out.printf("[%s] [Thread-%d] DEBUG: File absolute path: %s%n", Server.getTimeStamp(), threadId, requestedFile.getAbsolutePath());
        }
    }

    // =============================== POST Handlers ===============================

    private void handlePostRequest(String path, Map<String, String> headers, String requestBody, OutputStream out, String httpVersion, String connectionHeader) throws IOException {
        long threadId = Thread.currentThread().getId();
        
        if (!path.endsWith("/upload")) {
            HttpResponseBuilder.sendErrorResponse(out, httpVersion, 404, "Not Found", "POST endpoint not found.", false, threadId);
            return;
        }

        // 1. Content-Type Check (Requirement 6)
        String contentType = headers.getOrDefault("Content-Type", "").toLowerCase();
        if (!contentType.contains("application/json")) {
            HttpResponseBuilder.sendErrorResponse(out, httpVersion, 415, "Unsupported Media Type", "Server only accepts application/json.", false, threadId);
            return;
        }

        // 2. JSON Validation (Basic check) (Requirement 6)
        String jsonString = requestBody.trim();
        if (jsonString.isEmpty() || !jsonString.startsWith("{") || !jsonString.endsWith("}")) {
            HttpResponseBuilder.sendErrorResponse(out, httpVersion, 400, "Bad Request", "Invalid or missing JSON body.", false, threadId);
            return;
        }

        // 3. File Creation (Requirement 6)
        String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(java.time.LocalDateTime.now());
        String randomId = Long.toHexString(Double.doubleToLongBits(Math.random())).substring(0, 4);
        String filename = String.format("upload_%s_%s.json", timestamp, randomId);
        String relativePath = "/uploads/" + filename;
        
        File uploadDir = new File(Server.RESOURCES_DIR, "uploads");
        if (!uploadDir.exists()) uploadDir.mkdirs();
        File newFile = new File(uploadDir, filename);

        try (FileWriter fileWriter = new FileWriter(newFile)) {
            fileWriter.write(jsonString);
        } catch (IOException e) {
            System.err.printf("[%s] [Thread-%d] Error saving uploaded file: %s%n", Server.getTimeStamp(), threadId, e.getMessage());
            HttpResponseBuilder.sendErrorResponse(out, httpVersion, 500, "Internal Server Error", "Could not save file on the server.", false, threadId);
            return;
        }

        // 4. Success Response (201 Created) (Requirement 6, 9)
        String responseJson = String.format(
            "{\r\n" +
            "    \"status\": \"success\",\r\n" +
            "    \"message\": \"File created successfully\",\r\n" +
            "    \"filepath\": \"%s\"\r\n" +
            "}", relativePath);

        HttpResponseBuilder.sendJsonResponse(out, httpVersion, responseJson, connectionHeader, relativePath, path, threadId);
    }
}