package indexer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class InvertedIndex {
    private final Map<String, PostingList> postingsByTerm = new LinkedHashMap<>();
    private final Set<String> documents = new LinkedHashSet<>();
    private final Map<String, Integer> docLengths = new LinkedHashMap<>();

    public void addDocument(String doc, List<String> tokens) {
        if (doc == null || tokens == null) return;
        documents.add(doc);
        docLengths.put(doc, tokens.size());
        for (String term : tokens) {
            if (term == null || term.isEmpty()) continue;
            PostingList pl = postingsByTerm.get(term);
            if (pl == null) {
                pl = new PostingList();
                postingsByTerm.put(term, pl);
            }
            pl.increment(doc);
        }
    }

    public void add(String doc, String term) {
        if (doc == null || term == null) return;
        documents.add(doc);
        docLengths.merge(doc, 1, Integer::sum);
        PostingList pl = postingsByTerm.get(term);
        if (pl == null) {
            pl = new PostingList();
            postingsByTerm.put(term, pl);
        }
        pl.increment(doc);
    }

    public int totalDocs() {
        return documents.size();
    }

    public PostingList getPostings(String term) {
        PostingList pl = postingsByTerm.get(term);
        if (pl == null) {
            return new PostingList();
        }
        return pl;
    }

    public Set<String> terms() {
        return Collections.unmodifiableSet(postingsByTerm.keySet());
    }

    public Map<String, PostingList> asMapView() {
        return Collections.unmodifiableMap(postingsByTerm);
    }

    public int docLength(String doc) {
        return docLengths.getOrDefault(doc, 0);
    }

    public double avgDocLength() {
        if (docLengths.isEmpty()) return 0.0;
        long sum = 0;
        for (int v : docLengths.values()) sum += v;
        return (double) sum / docLengths.size();
    }

    public Map<String, Integer> docLengths() {
        return Collections.unmodifiableMap(docLengths);
    }
}
