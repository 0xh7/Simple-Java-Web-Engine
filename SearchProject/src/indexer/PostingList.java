package indexer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class PostingList {
    private final Map<String, Integer> freqByDoc = new LinkedHashMap<>();

    public void increment(String doc) {
        freqByDoc.merge(doc, 1, Integer::sum);
    }

    public int df() {
        return freqByDoc.size();
    }

    public boolean isEmpty() {
        return freqByDoc.isEmpty();
    }

    public Set<Map.Entry<String, Integer>> entries() {
        return Collections.unmodifiableSet(freqByDoc.entrySet());
    }
}
