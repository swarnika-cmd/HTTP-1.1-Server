/** Maps file extensions to Content-Type values and decides which files we serve. */
public class MimeTypeResolver {

    public static String getContentType(String fileName) {
        String name = fileName.toLowerCase();
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        } else if (name.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        } else if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        } else if (name.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        } else if (name.endsWith(".png")) {
            return "image/png";
        } else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (name.endsWith(".json")) {
            return "application/json";
        } else {
            return "application/octet-stream";
        }
    }

    /** These files are sent with Content-Disposition: attachment so the browser downloads them. */
    public static boolean isBinaryDownload(String fileName) {
        String name = fileName.toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    /** Only these file types are served from the web root; anything else gets 403. */
    public static boolean isSupportedType(String fileName) {
        String name = fileName.toLowerCase();
        return name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".css") || name.endsWith(".js")
            || isBinaryDownload(name);
    }
}
