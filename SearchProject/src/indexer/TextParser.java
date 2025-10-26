import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TextParser {
    public List<String> parse(String htmlContent) {
        List<String> tokens = new ArrayList<>();
        if (htmlContent == null || htmlContent.isEmpty()) return tokens;

        String text = htmlContent
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ");

        text = text.replaceAll("(?is)<[^>]+>", " ");

        text = text.replace("&nbsp;", " ")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">");

        text = text.toLowerCase(Locale.ROOT);

        String[] words = text.split("[^\\p{L}\\p{N}]+");

        for (String w : words) {
            if (w.length() > 1) tokens.add(w);
        }
        return tokens;
    }
}
