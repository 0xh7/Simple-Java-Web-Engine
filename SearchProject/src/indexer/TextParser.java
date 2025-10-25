import java.util.ArrayList;
import java.util.List;

public class TextParser {
    public List<String> parse(String htmlContent) {
        List<String> tokens = new ArrayList<>();
        if (htmlContent == null || htmlContent.isEmpty()) return tokens;

        String text = htmlContent
                .replaceAll("<script[^>]*>[\\s\\S]*?</script>", " ")
                .replaceAll("<style[^>]*>[\\s\\S]*?</style>", " ");

        text = text.replaceAll("<[^>]+>", " ");
        text = text.replaceAll("&nbsp;", " ");
        text = text.toLowerCase();

        String[] words = text.split("\\W+");
        for (String word : words) {
            if (word.length() > 1) {
                tokens.add(word);
            }
        }

        return tokens;
    }
}
