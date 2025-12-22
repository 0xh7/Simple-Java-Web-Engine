package util;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public final class Utils {

    private static final Set<String> BINARY_OR_NON_HTML_EXTS = new HashSet<>();
    static {
        String[] exts = {
            "png","jpg","jpeg","gif","svg","ico","webp","avif","bmp","tiff",
            "mp4","avi","mov","mkv","webm",
            "mp3","ogg","wav","flac","m4a",
            "pdf","zip","rar","7z","gz","tar","xz",
            "ttf","otf","woff","woff2",
            "css","js","map",
            "xml"
        };
        for (String e : exts) BINARY_OR_NON_HTML_EXTS.add(e);
    }

    private Utils() {}

    public static String sanitizeFileName(String input) {
        if (input == null || input.isEmpty()) return "_";
        return input.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    public static String extLower(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        if (q >= 0) url = url.substring(0, q);
        int h = url.indexOf('#');
        if (h >= 0) url = url.substring(0, h);
        int dot = url.lastIndexOf('.');
        if (dot < 0 || dot == url.length() - 1) return "";
        return url.substring(dot + 1).toLowerCase();
    }
    
    public static boolean isNonHtmlResource(String url) {
        String ext = extLower(url);
        return BINARY_OR_NON_HTML_EXTS.contains(ext);
    }

    public static void ensureParentDirs(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    public static BufferedWriter newUtf8Writer(Path path, OpenOption... options) throws IOException {
        ensureParentDirs(path);
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8, options);
    }

    public static void writeAtomic(Path target, Consumer<BufferedWriter> writerConsumer) throws IOException {
        ensureParentDirs(target);
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try (BufferedWriter w = newUtf8Writer(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writerConsumer.accept(w);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static String shortHex(String s) {
        if (s == null) return "00000000";
        int h = s.hashCode();
        return String.format(Locale.ROOT, "%08x", h);
    }

    public static String sha256(String input) {
        if (input == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
