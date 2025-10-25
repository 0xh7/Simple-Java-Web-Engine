import java.util.*;

public class Search {

    public List<String> search(String query, Map<String, Map<String, Integer>> index) {
        if (query == null || query.isEmpty()) return Collections.emptyList();

        int N = countDocs(index);

        Map<String, Double> scores = new HashMap<>();
        String[] terms = query.toLowerCase().split("\\W+");

        for (String term : terms) {
            if (term.isEmpty()) continue;
            Map<String, Integer> postings = index.get(term);
            if (postings == null || postings.isEmpty()) continue;

            int df = postings.size();
            double idf = Math.log(1.0 + (N / (double) (df + 1)));

            for (Map.Entry<String, Integer> e : postings.entrySet()) {
                double tf = 1.0 + Math.log(e.getValue());
                scores.merge(e.getKey(), tf * idf, Double::sum);
            }
        }

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort(
                Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
                        .reversed()
                        .thenComparing(Map.Entry::getKey)
        );

        List<String> result = new ArrayList<>(ranked.size());
        for (Map.Entry<String, Double> e : ranked) {
            result.add(e.getKey());
        }

        return result;
    }

    public List<String> searchLegacy(String query, Map<String, Set<String>> legacyIndex) {
        Map<String, Map<String, Integer>> converted = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : legacyIndex.entrySet()) {
            Map<String, Integer> m = new HashMap<>();
            for (String page : e.getValue()) {
                m.put(page, 1);
            }
            converted.put(e.getKey(), m);
        }
        return search(query, converted);
    }

    private int countDocs(Map<String, Map<String, Integer>> index) {
        Set<String> docs = new HashSet<>();
        for (Map<String, Integer> postings : index.values()) {
            docs.addAll(postings.keySet());
        }
        return docs.isEmpty() ? 1 : docs.size();
    }
}
