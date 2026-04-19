public class MimeTypeResolver {

    public static String getContentType(String fileName) {
        String lowerCaseName = fileName.toLowerCase();
        if (lowerCaseName.endsWith(".html") || lowerCaseName.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        } else if (lowerCaseName.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        } else if (lowerCaseName.endsWith(".png")) {
            return "image/png";
        } else if (lowerCaseName.endsWith(".jpg") || lowerCaseName.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lowerCaseName.endsWith(".json")) {
            return "application/json; charset=utf-8";
        } else {
            return "application/octet-stream";
        }
    }

    public static boolean isBinaryDownload(String fileName) {
        String lowerCaseName = fileName.toLowerCase();
        return lowerCaseName.endsWith(".txt") || 
               lowerCaseName.endsWith(".png") || 
               lowerCaseName.endsWith(".jpg") || 
               lowerCaseName.endsWith(".jpeg");
    }
    
    public static boolean isSupportedType(String fileName) {
        String lowerCaseName = fileName.toLowerCase();
        return lowerCaseName.endsWith(".html") || 
               lowerCaseName.endsWith(".txt") || 
               lowerCaseName.endsWith(".png") || 
               lowerCaseName.endsWith(".jpg") || 
               lowerCaseName.endsWith(".jpeg");
    }
}
