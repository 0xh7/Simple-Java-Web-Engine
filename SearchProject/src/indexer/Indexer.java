import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class Indexer {
    private final Map<String, Map<String, Integer>> index = new HashMap<>();

    public void addPage(String pageName, String htmlContent) {
        TextParser parser = new TextParser();
        for (String word : parser.parse(htmlContent)) {
            index.computeIfAbsent(word, k -> new HashMap<>())
                 .merge(pageName, 1, Integer::sum);
        }
    }

    public void save(String filePath) {
        try {
            Path finalPath = Path.of(filePath);
            Path dir = finalPath.getParent();
            if (dir != null) Files.createDirectories(dir);

            String date = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            Path backup = dir.resolve("index_backup_" + date + ".txt");
            if (Files.exists(finalPath)) {
                Files.copy(finalPath, backup, StandardCopyOption.REPLACE_EXISTING);
            }

            Path tmp = finalPath.resolveSibling(finalPath.getFileName() + ".tmp");
            try (FileWriter writer = new FileWriter(tmp.toFile())) {
                for (Map.Entry<String, Map<String, Integer>> entry : index.entrySet()) {
                    String word = entry.getKey();
                    Map<String, Integer> pages = entry.getValue();
                    writer.write(word + ":");
                    for (Map.Entry<String, Integer> page : pages.entrySet()) {
                        writer.write(page.getKey() + "(" + page.getValue() + "),");
                    }
                    writer.write("\n");
                }
            }

            Files.move(tmp, finalPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException e) {
            System.err.println("Error saving index: " + e.getMessage());
        }
    }

    public Map<String, Map<String, Integer>> getIndex() {
        return index;
    }
}
