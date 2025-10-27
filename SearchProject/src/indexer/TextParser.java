package indexer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TextParser {
    public List<String> parse(String htmlContent) {
        List<String> tokens = new ArrayList<>();
        if (htmlContent == null || htmlContent.isEmpty()) {
            return tokens;
        }

        String text = htmlContent
                .replaceAll("(?is)<script[^>]*?>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*?>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ");
        

        text = text.replace("&nbsp;", " ")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;", "'")
                   .replace("&#x27;", "'");

        text = text.toLowerCase(Locale.ROOT);

        String[] raw = text.split("[^\\p{L}\\p{N}'-]+");

        for (String r : raw) {
            if (r == null || r.isEmpty()) continue;
            String w = r.replaceAll("^[\'-]+|[\'-]+$", "");
            if (w.isEmpty()) continue;
            if (w.length() > 1) {
                tokens.add(w);
            } else {
                char c = w.charAt(0);
                if (Character.isLetterOrDigit(c)) {
                    tokens.add(w);
                }
            }
        }

        return tokens;
    }
}
