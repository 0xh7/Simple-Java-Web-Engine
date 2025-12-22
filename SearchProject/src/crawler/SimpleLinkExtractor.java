package crawler;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import util.Logger;

public class SimpleLinkExtractor implements LinkExtractor {

    private static final Pattern HREF_PATTERN =
        Pattern.compile("(?is)<\\s*(a|area|link)\\b[^>]*?\\bhref\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    private static final Pattern SRC_PATTERN =
        Pattern.compile("(?is)<\\s*(img|script|iframe)\\b[^>]*?\\bsrc\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    private static final Pattern META_REFRESH_URL =
        Pattern.compile("(?is)<meta\\s+[^>]*http-equiv\\s*=\\s*(?:\"refresh\"|'refresh'|refresh)[^>]*content\\s*=\\s*(?:\"|')\\s*\\d+\\s*;\\s*url\\s*=\\s*([^\"'>\\s;]+)");

    @Override
    public List<String> extractLinks(String htmlContent, String baseUrl) {
        List<String> links = new ArrayList<>();
        if (htmlContent == null || baseUrl == null || baseUrl.isEmpty()) return links;

        Set<String> uniq = new HashSet<>();
        URI base;
        try {
            base = new URI(baseUrl);
        } catch (URISyntaxException e) {
            Logger.warn("Invalid base URL: " + baseUrl);
            return links;
        }

        extractWithPattern(HREF_PATTERN, htmlContent, base, uniq, links);
        extractWithPattern(SRC_PATTERN, htmlContent, base, uniq, links);

        Matcher mr = META_REFRESH_URL.matcher(htmlContent);
        while (mr.find()) {
            resolveAdd(base, mr.group(1), uniq, links);
        }

        return links;
    }

    private static void extractWithPattern(Pattern p, String html, URI base, Set<String> uniq, List<String> out) {
        Matcher m = p.matcher(html);
        while (m.find()) {
            String raw = firstNonNull(m.group(3), m.group(4), m.group(5));
            if (raw == null) continue;
            raw = raw.trim();
            if (raw.isEmpty()) continue;
            if (raw.startsWith("#")) continue;

            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.startsWith("mailto:")) continue;
            if (lower.startsWith("javascript:")) continue;

            resolveAdd(base, raw, uniq, out);
        }
    }

    private static void resolveAdd(URI base, String raw, Set<String> uniq, List<String> out) {
        try {
            URI resolved = base.resolve(raw);
            String abs = resolved.toString();
            if (abs.startsWith("http://") || abs.startsWith("https://")) {
                if (uniq.add(abs)) out.add(abs);
            }
        } catch (Exception e) {
            Logger.debug("Failed to resolve URL: " + raw + " against base " + base + " (" + e.getMessage() + ")");
        }
    }

    private static String firstNonNull(String... arr) {
        for (String s : arr) if (s != null) return s;
        return null;
    }
}
