package search;
import java.util.*;
import java.util.regex.Pattern;
import indexer.Indexer;
import indexer.InvertedIndex;
import indexer.PostingList;
import indexer.TextParser;

public class Search {

    public static final class SearchResult {
        private final String document;
        private final double score;
        private final String title;
        private final String snippet;
        private final String url;

        public SearchResult(String document, double score, String title, String snippet, String url) {
            this.document = document;
            this.score = score;
            this.title = title;
            this.snippet = snippet;
            this.url = url;
        }

        public String document() { return document; }
        public double score() { return score; }
        public String title() { return title; }
        public String snippet() { return snippet; }
        public String url() { return url; }
    }

    private static final Set<String> STOPWORDS = Set.of(
            "the","an","and","or","of","to","in","on","for","mit","und","der","die","das"
    );

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    public List<String> search(String query, InvertedIndex index) {
        List<SearchResult> detailed = searchDetailed(query, index, Collections.emptyMap());
        List<String> docs = new ArrayList<>(detailed.size());
        for (SearchResult r : detailed) {
            docs.add(r.document());
        }
        return docs;
    }

    public List<SearchResult> searchDetailed(String query,
                                             InvertedIndex index,
                                             Map<String, Indexer.DocumentInfo> metadata) {
        if (query == null || query.isEmpty() || index == null) return Collections.emptyList();

        String phrase = query.toLowerCase(Locale.ROOT).trim();
        List<String> qTokens = tokenize(query);
        if (qTokens.isEmpty()) return Collections.emptyList();

        int N = Math.max(1, index.totalDocs());
        double avgLen = index.avgDocLength();
        if (avgLen <= 0) avgLen = 1.0;

        Map<String, Double> scores = new HashMap<>();
        List<String> termsForHighlight = new ArrayList<>(qTokens);

        long now = System.currentTimeMillis();

        for (String term : qTokens) {
            PostingList postings = index.getPostings(term);
            if (postings == null || postings.isEmpty()) continue;

            int df = postings.df();
            double idf = Math.log(1.0 + (N - df + 0.5) / (df + 0.5));
            if (idf <= 0) continue;

            for (Map.Entry<String, Integer> e : postings.entries()) {
                String doc = e.getKey();
                int tf = e.getValue();
                int dl = index.docLength(doc);
                double norm = tf * (K1 + 1.0) / (tf + K1 * (1.0 - B + B * dl / avgLen));
                double base = idf * norm;
                Indexer.DocumentInfo meta = metadata.get(doc);
                if (meta != null && meta.title() != null && !meta.title().isBlank()) {
                    String lowTitle = meta.title().toLowerCase(Locale.ROOT);
                    if (lowTitle.contains(term)) {
                        base *= 1.3; 
                    }
                }
                scores.merge(doc, base, Double::sum);
            }
        }

        List<SearchResult> ranked = new ArrayList<>(scores.size());
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            Indexer.DocumentInfo meta = metadata.get(e.getKey());
            String title = meta != null ? meta.title() : "";
            String rawSnippet = meta != null ? meta.snippet() : "";
            String url = meta != null ? firstNonBlank(meta.canonicalUrl(), meta.url(), e.getKey()) : e.getKey();
            String highlighted = highlight(rawSnippet, termsForHighlight);

            double linkBoost = 0.0;
            double freshBoost = 0.0;
            double phraseBoost = 0.0;
            double prBoost = 0.0;

            if (meta != null) {
                int inlinks = Math.max(0, meta.inlinks());
                linkBoost = Math.log1p(inlinks) / 5.0;

                if (meta.fetchedAt() > 0) {
                    double ageDays = (now - meta.fetchedAt()) / 86_400_000.0;
                    freshBoost = 0.2 * Math.exp(-ageDays / 30.0);
                }

                if (!phrase.isBlank()) {
                    String lowTitle = title.toLowerCase(Locale.ROOT);
                    String lowSnippet = rawSnippet.toLowerCase(Locale.ROOT);
                    if (lowTitle.contains(phrase) || lowSnippet.contains(phrase)) {
                        phraseBoost = 0.15;
                    }
                }

                prBoost = meta.pageRank() * 0.5; 
            }

            double score = e.getValue() * (1.0 + linkBoost + freshBoost + phraseBoost) + prBoost;
            ranked.add(new SearchResult(e.getKey(), score, title, highlighted, url));
        }

        ranked.sort(
                Comparator.<SearchResult>comparingDouble(SearchResult::score)
                        .reversed()
                        .thenComparing(SearchResult::document)
        );

        return ranked;
    }

  
    public String suggest(String query, InvertedIndex index) {
        if (query == null || query.isBlank() || index == null) return query;
        List<String> qTokens = tokenize(query);
        if (qTokens.isEmpty()) return query;

        List<String> suggestionTokens = new ArrayList<>();
        for (String token : qTokens) {
            String best = token;
            int bestDist = Integer.MAX_VALUE;
            for (String term : index.terms()) {
                int dist = editDistance(token, term, 2);
                if (dist >= 0 && dist < bestDist) {
                    bestDist = dist;
                    best = term;
                    if (bestDist == 0) break;
                }
            }
            suggestionTokens.add(best);
        }
        String suggestion = String.join(" ", suggestionTokens);
        return suggestion;
    }

    private static int editDistance(String a, String b, int maxDist) {
        if (a.equals(b)) return 0;
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > maxDist) return -1;
        int[] prev = new int[lb + 1];
        int[] curr = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            curr[0] = i;
            int minRow = curr[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (curr[j] < minRow) minRow = curr[j];
            }
            if (minRow > maxDist) return -1;
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[lb] > maxDist ? -1 : prev[lb];
    }

    private static List<String> tokenize(String query) {
        return TextParser.tokenizePlain(query, STOPWORDS);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private static String highlight(String text, List<String> terms) {
        if (text == null || text.isBlank() || terms == null || terms.isEmpty()) return text == null ? "" : text;
        String result = text;
        for (String t : terms) {
            if (t == null || t.isBlank()) continue;
            String escaped = Pattern.quote(t);
            result = result.replaceAll("(?i)\\b" + escaped + "\\b", "[" + t + "]");
        }
        return result;
    }
}
