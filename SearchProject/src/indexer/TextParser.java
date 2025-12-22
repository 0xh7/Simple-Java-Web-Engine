package indexer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextParser {

    public static final class ParseResult {
        private final List<String> tokens;
        private final String title;
        private final String snippet;

        public ParseResult(List<String> tokens, String title, String snippet) {
            this.tokens = tokens;
            this.title = title;
            this.snippet = snippet;
        }

        public List<String> tokens() { return tokens; }
        public String title() { return title; }
        public String snippet() { return snippet; }
    }

    private static final Pattern TITLE = Pattern.compile("(?is)<title>(.*?)</title>");
    private static final Pattern META_DESC = Pattern.compile(
            "(?is)<meta\\s+[^>]*(?:(?=[^>]*\\bname\\s*=\\s*\"description\")|(?=[^>]*\\bproperty\\s*=\\s*\"og:description\"))(?=[^>]*\\bcontent\\s*=\\s*\"([^\"]*)\")[^>]*>"
    );
    private static final Set<String> IMPORTANT_SINGLE_CHARS =
            Set.of("a", "b", "c", "d", "e", "x", "u", "v");

    public List<String> parse(String htmlContent) {
        return parseWithMetadata(htmlContent).tokens();
    }

    public ParseResult parseWithMetadata(String htmlContent) {
        if (htmlContent == null || htmlContent.isEmpty()) {
            return new ParseResult(new ArrayList<>(), "", "");
        }

        String title = extractSingle(TITLE, htmlContent);
        String description = extractSingle(META_DESC, htmlContent);

        String text = htmlContent
                .replaceAll("(?is)<script[^>]*?>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*?>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ");

        String normalized = decodeCommonEntities(text).toLowerCase(Locale.ROOT);
        List<String> tokens = tokenizeNormalized(normalized, null);
        String snippet = buildSnippet(description, normalized);

        return new ParseResult(tokens, title, snippet);
    }

    private static String extractSingle(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    private static String decodeCommonEntities(String text) {
        return text.replace("&nbsp;", " ")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;", "'")
                   .replace("&#x27;", "'");
    }

    private static String buildSnippet(String description, String plainText) {
        String src = (description != null && !description.isBlank())
                ? description.trim()
                : plainText.trim();
        if (src.length() > 220) {
            return src.substring(0, 217).trim() + "...";
        }
        return src;
    }

    public static List<String> tokenizePlain(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<>();
        String normalized = decodeCommonEntities(text).toLowerCase(Locale.ROOT);
        return tokenizeNormalized(normalized, null);
    }

    public static List<String> tokenizePlain(String text, Set<String> stopwords) {
        if (text == null || text.isEmpty()) return new ArrayList<>();
        String normalized = decodeCommonEntities(text).toLowerCase(Locale.ROOT);
        return tokenizeNormalized(normalized, stopwords);
    }

    private static List<String> tokenizeNormalized(String normalized, Set<String> stopwords) {
        List<String> tokens = new ArrayList<>();
        String[] raw = normalized.split("[^\\p{L}\\p{N}'-]+");
        for (String r : raw) {
            if (r == null || r.isEmpty()) continue;
            String w = r.replaceAll("^[\'-]+|[\'-]+$", "");
            if (w.isEmpty()) continue;
            boolean keep = w.length() > 1 || IMPORTANT_SINGLE_CHARS.contains(w);
            if (!keep) continue;
            if (stopwords != null && stopwords.contains(w) && !IMPORTANT_SINGLE_CHARS.contains(w)) {
                continue;
            }
            tokens.add(w);
        }
        return tokens;
    }
}
