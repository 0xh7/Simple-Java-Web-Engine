package crawler;
import java.util.List;

public interface LinkExtractor {
    List<String> extractLinks(String htmlContent, String baseUrl);
}
