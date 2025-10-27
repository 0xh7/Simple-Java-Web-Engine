package search;
import java.util.*;
import indexer.InvertedIndex;
import indexer.PostingList;

public class Search {

    public List<String> search(String query, InvertedIndex index) {
        if (query == null || query.isEmpty() || index == null) return Collections.emptyList();
        final int N = Math.max(0, index.totalDocs());

        Map<String, Double> scores = new HashMap<>();
        String[] terms = query.toLowerCase().split("\\W+");

        for (String term : terms) {
            if (term.isEmpty()) continue;
            PostingList postings = index.getPostings(term);
            if (postings == null || postings.isEmpty()) continue;

            int df = postings.df();
            double idf = Math.log((N + 1.0) / (df + 1.0)) + 1.0;

            for (Map.Entry<String, Integer> e : postings.entries()) {
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
        for (Map.Entry<String, Double> e : ranked) result.add(e.getKey());
        return result;
    }
}
