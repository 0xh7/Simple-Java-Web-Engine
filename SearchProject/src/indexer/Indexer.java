package indexer;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import util.Logger;
import util.Utils;

public class Indexer {
    private final InvertedIndex index = new InvertedIndex();
    private final Map<String, DocumentInfo> metadata = new LinkedHashMap<>();

    public static final class DocumentInfo {
        private final String title;
        private final String snippet;
        private final int length;
        private final String url;
        private final String canonicalUrl;
        private final int inlinks;
        private final long fetchedAt;
        private final double pageRank;

        public DocumentInfo(String title,
                            String snippet,
                            int length,
                            String url,
                            String canonicalUrl,
                            int inlinks,
                            long fetchedAt,
                            double pageRank) {
            this.title = title;
            this.snippet = snippet;
            this.length = length;
            this.url = url;
            this.canonicalUrl = canonicalUrl;
            this.inlinks = inlinks;
            this.fetchedAt = fetchedAt;
            this.pageRank = pageRank;
        }

        public String title() { return title; }
        public String snippet() { return snippet; }
        public int length() { return length; }
        public String url() { return url; }
        public String canonicalUrl() { return canonicalUrl; }
        public int inlinks() { return inlinks; }
        public long fetchedAt() { return fetchedAt; }
        public double pageRank() { return pageRank; }
    }

    public void addPage(String pageName,
                        String url,
                        String canonicalUrl,
                        String htmlContent,
                        int inlinks,
                        long fetchedAtMillis,
                        double pageRank,
                        java.util.List<String> anchorTexts) {
        final int anchorBoost = 2; 
        if (htmlContent == null) return;
        TextParser parser = new TextParser();
        TextParser.ParseResult res = parser.parseWithMetadata(htmlContent);

        java.util.List<String> combined = new java.util.ArrayList<>(res.tokens());
        if (anchorTexts != null && !anchorTexts.isEmpty()) {
            java.util.List<String> anchorTokens = new java.util.ArrayList<>();
            for (String a : anchorTexts) {
                anchorTokens.addAll(TextParser.tokenizePlain(a));
            }
            for (int i = 0; i < anchorBoost; i++) {
                combined.addAll(anchorTokens);
            }
        }

        index.addDocument(pageName, combined);
        metadata.put(pageName, new DocumentInfo(
                res.title(),
                res.snippet(),
                combined.size(),
                url,
                canonicalUrl,
                inlinks,
                fetchedAtMillis,
                pageRank
        ));
    }

    public void save(String filePath) {
        Path finalPath = Paths.get(filePath);
        try {
            Utils.ensureParentDirs(finalPath);

            if (Files.exists(finalPath)) {
                String date = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                Path backup = finalPath.getParent().resolve("index_backup_" + date + ".txt");
                Files.copy(finalPath, backup, StandardCopyOption.REPLACE_EXISTING);
            }

            Utils.writeAtomic(finalPath, (BufferedWriter w) -> {
                try {
                    for (String term : index.terms()) {
                        PostingList pl = index.getPostings(term);
                        if (pl.isEmpty()) continue;

                        w.write(term);
                        w.write(':');

                        boolean first = true;
                        for (Map.Entry<String, Integer> e : pl.entries()) {
                            if (!first) {
                                w.write(',');
                            }
                            w.write(e.getKey());
                            w.write('(');
                            w.write(String.valueOf(e.getValue()));
                            w.write(')');
                            first = false;
                        }
                        w.write('\n');
                    }
                } catch (IOException io) {
                    Logger.error("IOException while writing index to " + finalPath + ": " + io.getMessage(), io);
                }
            });

        } catch (Exception e) {
            Logger.error("Error saving index to " + finalPath + ": " + e.getMessage(), e);
        }
    }

    public InvertedIndex getIndex() {
        return index;
    }

    public Map<String, DocumentInfo> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }
}
