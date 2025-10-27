package indexer;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import util.Logger;
import util.Utils;

public class Indexer {
    private final InvertedIndex index = new InvertedIndex();

    public void addPage(String pageName, String htmlContent) {
        if (htmlContent == null) return;
        TextParser parser = new TextParser();
        for (String word : parser.parse(htmlContent)) {
            index.add(pageName, word);
        }
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
}
