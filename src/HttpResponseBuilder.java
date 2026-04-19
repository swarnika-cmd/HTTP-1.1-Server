import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

public class HttpResponseBuilder {
    
    // Returns date in RFC 7231 format
    public static String getRfc1123Date() {
        return java.time.ZonedDateTime.now(java.time.ZoneId.of("GMT"))
                .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
    }

    public static void sendErrorResponse(OutputStream out, String httpVersion, int statusCode, String statusText, String message, boolean is404, long threadId) throws IOException {
        String htmlBody = String.format("<html><body><h1>%d %s</h1><p>%s</p></body></html>", statusCode, statusText, message);
        
        String response = String.format(
            "%s %d %s\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: %d\r\n" +
            "Date: %s\r\n" +
            "Server: %s\r\n" +
            "Connection: close\r\n" + // Errors typically close connection
            "\r\n" +
            "%s",
            httpVersion, statusCode, statusText, htmlBody.getBytes("UTF-8").length, 
            getRfc1123Date(), Server.SERVER_NAME,
            htmlBody
        );
        out.write(response.getBytes());
        out.flush();

        String statusLog = (is404 ? "File Not Found (404)" : statusText);
        System.out.printf("[%s] [Thread-%d] Response: %d %s (%s)%n",
            Server.getTimeStamp(), threadId, statusCode, statusText, statusLog);
    }

    public static void sendFileResponse(OutputStream out, String httpVersion, File requestedFile, String connectionHeader, long threadId) throws IOException {
        String fileName = requestedFile.getName();
        String contentType = MimeTypeResolver.getContentType(fileName);
        boolean isBinaryDownload = MimeTypeResolver.isBinaryDownload(fileName);

        long fileSize = requestedFile.length();
        String connection = "keep-alive".equalsIgnoreCase(connectionHeader) ? "keep-alive" : "close";

        StringBuilder responseHeaders = new StringBuilder();
        responseHeaders.append(String.format("%s 200 OK\r\n", httpVersion));
        responseHeaders.append(String.format("Content-Type: %s\r\n", contentType));
        responseHeaders.append(String.format("Content-Length: %d\r\n", fileSize));
        responseHeaders.append(String.format("Date: %s\r\n", getRfc1123Date()));
        responseHeaders.append(String.format("Server: %s\r\n", Server.SERVER_NAME));
        responseHeaders.append(String.format("Connection: %s\r\n", connection));

        if (!isBinaryDownload) {
            responseHeaders.append("Keep-Alive: timeout=30, max=100\r\n");
        } else {
            responseHeaders.append(String.format("Content-Disposition: attachment; filename=\"%s\"\r\n", fileName));
        }
        responseHeaders.append("\r\n");
        
        out.write(responseHeaders.toString().getBytes());
        
        try (FileInputStream fileIn = new FileInputStream(requestedFile)) {
            byte[] buffer = new byte[8192]; 
            int bytesRead;
            while ((bytesRead = fileIn.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } catch (IOException e) {
            System.err.printf("[%s] [Thread-%d] Error reading/sending file: %s%n", Server.getTimeStamp(), threadId, e.getMessage());
        }
        
        System.out.printf("[%s] [Thread-%d] Sending %s: %s (%d bytes) ✓%n",
            Server.getTimeStamp(), threadId, isBinaryDownload ? "binary file" : "HTML", fileName, fileSize);
        System.out.printf("[%s] [Thread-%d] Response: 200 OK (%d bytes transferred)%n", Server.getTimeStamp(), threadId, fileSize);
    }

    public static void sendJsonResponse(OutputStream out, String httpVersion, String responseJson, String connectionHeader, String relativePath, String path, long threadId) throws IOException {
        String connection = "keep-alive".equalsIgnoreCase(connectionHeader) ? "keep-alive" : "close";

        String response = String.format(
            "%s 201 Created\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: %d\r\n" +
            "Date: %s\r\n" +
            "Server: %s\r\n" +
            "Connection: %s\r\n" +
            "\r\n" +
            "%s",
            httpVersion, responseJson.getBytes("UTF-8").length, getRfc1123Date(), Server.SERVER_NAME, connection, responseJson
        );
        out.write(response.getBytes());
        out.flush();
        
        System.out.printf("[%s] [Thread-%d] POST to %s. File created: %s. Response: 201 Created%n",
            Server.getTimeStamp(), threadId, path, relativePath);
    }
}
