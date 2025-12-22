package crawler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import util.Logger;

public class PageDownloader {
    private static final int MAX_BYTES = 2_500_000; 
    private static final int SNIFF_BYTES = 2048;

    private static final Pattern META_CHARSET =
            Pattern.compile("(?is)<meta[^>]+charset\\s*=\\s*\"?([a-zA-Z0-9._-]+)\"?");
    private static final Pattern ANCHOR_TAG =
            Pattern.compile("(?is)<a\\s+[^>]*href\\s*=");
    private static final Pattern BIG_SCRIPT =
            Pattern.compile("(?is)<script[^>]*>[^<]{4000,}");
    private static final String USER_AGENT =
    "Mozilla/5.0 (compatible; ZeroXBot/1.0; " +
    "+https://github.com/0xh7/Simple-Java-Web-Engine; " +
    "Research crawler; Contact: https://github.com/0xh7/Simple-Java-Web-Engine)";


    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ExecutorService renderExecutor = Executors.newFixedThreadPool(2);

    public String download(String url) {
        return downloadInternal(url, false);
    }

    public String downloadAllowingXml(String url) {
        return downloadInternal(url, true);
    }

    public String downloadHybrid(String url) {
        return downloadInternal(url, false, true);
    }

    private String downloadInternal(String url, boolean allowXml) {
        return downloadInternal(url, allowXml, false);
    }

    private String downloadInternal(String url, boolean allowXml, boolean allowRender) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", allowXml
                            ? "application/xml,text/html,application/xhtml+xml;q=0.9,*/*;q=0.1"
                            : "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
                    .GET()
                    .build();

            HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = res.statusCode();

            if (code >= 200 && code < 300) {
                byte[] body = res.body();
                if (body.length > MAX_BYTES) {
                    Logger.warn("Skipping huge page (" + body.length + " bytes): " + url);
                    return "";
                }

                String contentType = res.headers().firstValue("Content-Type").orElse("");
                String lower = contentType.toLowerCase(Locale.ROOT);

                boolean isHtml = lower.contains("text/html") || lower.contains("application/xhtml+xml");
                boolean isXml = lower.contains("application/xml") || lower.contains("text/xml");
                if (contentType.isBlank()) {
                    isHtml = looksLikeHtml(body);
                    isXml = looksLikeXml(body);
                }
                if (!isHtml && !(allowXml && isXml)) {
                    Logger.info("Skipping non-HTML: " + url + " (" + contentType + ")");
                    return "";
                }

                Charset cs = detectCharsetFromHeader(lower);
                String text = new String(body, cs);

                Charset fromMeta = sniffMetaCharset(text);
                if (fromMeta != null && !fromMeta.equals(cs)) {
                    text = new String(body, fromMeta);
                }

                if (allowRender && shouldRender(text)) {
                    try {
                        Future<String> renderedFuture = renderExecutor.submit(() -> renderWithHeadlessChrome(url));
                        String rendered = renderedFuture.get(10, TimeUnit.SECONDS);
                        if (rendered != null && !rendered.isBlank()) {
                            return rendered;
                        }
                    } catch (Exception re) {
                        Logger.debug("Headless render timeout/failure for " + url + ": " + re.getMessage());
                    }
                    String externalCmd = System.getenv("RENDER_CMD");
                    if (externalCmd != null && !externalCmd.isBlank()) {
                        String rendered = renderWithExternal(externalCmd, url);
                        if (rendered != null && !rendered.isBlank()) {
                            return rendered;
                        }
                    }
                }

                return text;
            } else {
                Logger.warn("Error downloading " + url + ": HTTP " + code);
            }
        } catch (Exception e) {
            Logger.error("Error downloading " + url + ": " + e.getMessage(), e);
        }
        return "";
    }

    private static Charset detectCharsetFromHeader(String lowerContentType) {
        Charset cs = StandardCharsets.UTF_8;
        int i = lowerContentType.indexOf("charset=");
        if (i >= 0) {
            String enc = lowerContentType.substring(i + 8).trim();
            enc = enc.replace("\"", "").replace("'", "");
            try {
                cs = Charset.forName(enc);
            } catch (Exception e) {
                Logger.debug("Invalid charset from header: " + enc);
            }
        }
        return cs;
    }

    private static Charset sniffMetaCharset(String text) {
        Matcher m = META_CHARSET.matcher(text);
        if (m.find()) {
            String enc = m.group(1);
            try {
                return Charset.forName(enc);
            } catch (Exception e) {
                Logger.debug("Invalid meta charset: " + enc);
            }
        }
        return null;
    }

    private static boolean shouldRender(String html) {
        if (html == null) return false;
        int len = html.length();

        boolean tiny = len < 5 * 1024;
        boolean noAnchors = !ANCHOR_TAG.matcher(html).find();
        boolean bigScript = BIG_SCRIPT.matcher(html).find();

        String lower = html.toLowerCase(Locale.ROOT);
        boolean spaHint = lower.contains("react") || lower.contains("next.js") ||
                lower.contains("nextjs") || lower.contains("vue") ||
                lower.contains("angular") || lower.contains("spa");

        return tiny || noAnchors || bigScript || spaHint;
    }

    private static boolean looksLikeHtml(byte[] body) {
        String head = sniffHead(body);
        String lower = head.toLowerCase(Locale.ROOT);
        return lower.contains("<!doctype html") ||
                lower.contains("<html") ||
                lower.contains("<head") ||
                lower.contains("<body");
    }

    private static boolean looksLikeXml(byte[] body) {
        String head = sniffHead(body).trim().toLowerCase(Locale.ROOT);
        return head.startsWith("<?xml") ||
                head.contains("<urlset") ||
                head.contains("<sitemapindex") ||
                head.contains("<rss") ||
                head.contains("<feed");
    }

    private static String sniffHead(byte[] body) {
        if (body == null || body.length == 0) return "";
        int len = Math.min(body.length, SNIFF_BYTES);
        return new String(body, 0, len, StandardCharsets.UTF_8);
    }

    private String renderWithHeadlessChrome(String url) {
        String[] candidates = {
                "chrome", "google-chrome", "chromium", "msedge", "chrome.exe", "msedge.exe"
        };

        for (String bin : candidates) {
            try {
                java.util.List<String> cmd = new ArrayList<>();
                cmd.add(bin);
                cmd.add("--headless=new");
                cmd.add("--disable-gpu");
                cmd.add("--dump-dom");
                cmd.add("--disable-extensions");
                cmd.add("--disable-dev-shm-usage");
                cmd.add("--hide-scrollbars");
                cmd.add("--mute-audio");
                cmd.add("--no-sandbox");
                cmd.add("--timeout=15000");
                cmd.add("--virtual-time-budget=7000");
                cmd.add(url);

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    int total = 0;
                    while ((line = r.readLine()) != null) {
                        sb.append(line).append('\n');
                        total += line.length();
                        if (total > MAX_BYTES) {
                            Logger.warn("Headless render output too large, skipping.");
                            break;
                        }
                    }
                }
                boolean exited = p.waitFor(18, java.util.concurrent.TimeUnit.SECONDS);
                if (!exited) {
                    p.destroyForcibly();
                    continue;
                }
                if (p.exitValue() == 0 && sb.length() > 0) {
                    Logger.debug("Rendered via headless Chrome: " + bin);
                    return sb.toString();
                }
            } catch (Exception e) {
                Logger.debug("Headless render failed with " + bin + ": " + e.getMessage());
            }
        }

        return null;
    }

    private String renderWithExternal(String cmdTemplate, String url) {
        try {
            String bin = cmdTemplate.trim();
            if (bin.isEmpty()) return null;
            if ((bin.startsWith("\"") && bin.endsWith("\"")) || (bin.startsWith("'") && bin.endsWith("'"))) {
                bin = bin.substring(1, bin.length() - 1).trim();
            }
            java.util.List<String> parts = new java.util.ArrayList<>();
            parts.add(bin);
            parts.add(url);
            ProcessBuilder pb = new ProcessBuilder(parts);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int total = 0;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                    total += line.length();
                    if (total > MAX_BYTES) break;
                }
            }
            boolean exited = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() == 0 && sb.length() > 0) {
                return sb.toString();
            }
        } catch (Exception e) {
            Logger.debug("External render failed: " + e.getMessage());
        }
        return null;
    }

    public void shutdown() {
        renderExecutor.shutdown();
        try {
            if (!renderExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                renderExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            renderExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
