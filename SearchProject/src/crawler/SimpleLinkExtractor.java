import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URI;

public class SimpleLinkExtractor implements LinkExtractor {
    
    private static final Pattern LINK_PATTERN =
            Pattern.compile("href\\s*=\\s*([\"'])([^\"']+)\\1", Pattern.CASE_INSENSITIVE);

    @Override
    public List<String> extractLinks(String htmlContent, String baseUrl) {
        List<String> links = new ArrayList<>();
        if (htmlContent == null || baseUrl == null) return links;

        Matcher matcher = LINK_PATTERN.matcher(htmlContent);

        while (matcher.find()) {
            String raw = matcher.group(2).trim();
            if (raw.isEmpty()) continue;
            if (raw.startsWith("#")) continue;            
            if (raw.startsWith("mailto:")) continue;        
            if (raw.startsWith("javascript:")) continue;     

            try {
                URI base = new URI(baseUrl);
                URI resolved = base.resolve(raw);             
                String abs = resolved.toString();
                if (abs.startsWith("http://") || abs.startsWith("https://")) {
                    links.add(abs);
                }
            } catch (Exception ignored) {}
        }

        return links;
    }
}
