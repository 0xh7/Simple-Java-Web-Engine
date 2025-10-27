package crawler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import util.Logger;

public class PageDownloader {
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public String download(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
                    .GET()
                    .build();

            HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = res.statusCode();

            if (code >= 200 && code < 300) {
                String contentType = res.headers().firstValue("Content-Type").orElse("");
                String lower = contentType.toLowerCase(Locale.ROOT);

                if (!lower.contains("text/html") && !lower.contains("application/xhtml+xml")) {
                    Logger.info("Skipping non-HTML: " + url + " (" + contentType + ")");
                    return "";
                }

                Charset cs = StandardCharsets.UTF_8;
                int i = lower.indexOf("charset=");
                if (i >= 0) {
                    String enc = lower.substring(i + 8).trim();
                    enc = enc.replace("\"", "").replace("'", "");
                    try {
                        cs = Charset.forName(enc);
                    } catch (Exception e) {
                        Logger.debug("Invalid charset: " + enc);
                    }
                }

                return new String(res.body(), cs);
            } else {
                Logger.warn("Error downloading " + url + ": HTTP " + code);
            }
        } catch (Exception e) {
            Logger.error("Error downloading " + url + ": " + e.getMessage(), e);
        }
        return "";
    }
}
