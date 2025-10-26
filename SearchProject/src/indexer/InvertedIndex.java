import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class InvertedIndex {
    private final Map<String, PostingList> postingsByTerm = new HashMap<>();
    private final Set<String> documents = new HashSet<>();

    public void add(String doc, String term) {
        documents.add(doc);
        postingsByTerm.computeIfAbsent(term, k -> new PostingList()).increment(doc);
    }

    public int totalDocs() {
        return documents.size();
    }

    public PostingList getPostings(String term) {
        return postingsByTerm.get(term);
    }

    public Set<String> terms() {
        return Collections.unmodifiableSet(postingsByTerm.keySet());
    }

    public Map<String, PostingList> asMapView() {
        return Collections.unmodifiableMap(postingsByTerm);
    }
}
