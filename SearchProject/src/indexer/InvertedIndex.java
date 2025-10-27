package indexer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class InvertedIndex {
    private final Map<String, PostingList> postingsByTerm = new LinkedHashMap<>();
    private final Set<String> documents = new LinkedHashSet<>();

    public void add(String doc, String term) {
        documents.add(doc);
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
}
