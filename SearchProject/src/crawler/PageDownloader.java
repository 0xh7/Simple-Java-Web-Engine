import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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
                    .GET()
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();

            if (code >= 200 && code < 300) {

            // safe safe i love safe 
                String contentType = res.headers()
                        .firstValue("Content-Type")
                        .orElse("");
                if (!contentType.contains("text/html")) {
                    System.out.println("Skipping non-HTML: " + url + " (" + contentType + ")");
                    return "";
                }

                return res.body();

            } else {
                System.out.println("Error downloading " + url + ": HTTP " + code);
            }

        } catch (Exception e) {
            System.out.println("Error downloading " + url + ": " + e.getMessage());
        }

        return "";
    }
}
