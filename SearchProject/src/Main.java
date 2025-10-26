import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        Logger.setLevel(Logger.Level.INFO);

        // defaults
        String seedUrl = "https://example.com";
        int depth = 1;
        String mode = "s";     // "m" أو "multi" للتوازي
        String query = "example";

        // seed depth [mode] [query]
        try {
            if (args.length >= 1 && args[0] != null && !args[0].isBlank()) {
                seedUrl = args[0];
            }

            if (args.length >= 2 && args[1] != null && !args[1].isBlank()) {
                try {
                    depth = Integer.parseInt(args[1].trim());
                    if (depth < 1) {
                        Logger.warn("Depth < 1; using default depth = 1");
                        depth = 1;
                    }
                } catch (NumberFormatException e) {
                    Logger.warn("Invalid depth value; using default depth = 1");
                    depth = 1;
                }
            }

            if (args.length >= 3 && args[2] != null && !args[2].isBlank()) {
                mode = args[2];
            }

            if (args.length >= 4 && args[3] != null && !args[3].isBlank()) {
                query = args[3];
            }

        } catch (Exception e) {
            Logger.warn("Error parsing arguments, falling back to defaults.");
        }

        boolean multi = mode.equalsIgnoreCase("m")
                      || mode.equalsIgnoreCase("multi")
                      || mode.equalsIgnoreCase("mt");

        String runId = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                .format(new java.util.Date());

        Path pagesDir = Path.of("data", "pages", runId);
        Path indexDir = Path.of("data", "index", runId);

        WebCrawlerGermany crawler = new WebCrawlerGermany(pagesDir, indexDir, multi);
        crawler.crawl(seedUrl, depth);

        crawler.saveDiscoveredHosts(indexDir.resolve("hosts.txt").toString());

        Logger.info("Discovered hosts:");
        for (String host : crawler.getDiscoveredHosts()) {
            Logger.info(" - " + host);
        }

        Indexer indexer = new Indexer();

        try {
            java.io.File[] pageFiles = pagesDir.toFile().listFiles();
            if (pageFiles != null) {
                for (java.io.File file : pageFiles) {
                    try {
                        if (!file.isFile()) continue;
                        String nameLower = file.getName().toLowerCase(java.util.Locale.ROOT);
                        if (!(nameLower.endsWith(".html") || nameLower.endsWith(".htm"))) continue;

                        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                        indexer.addPage(file.getName(), content);
                    } catch (Exception e) {
                        Logger.warn("Error reading page file: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            Logger.error("Error scanning pages directory: " + e.getMessage(), e);
        }

        Path indexPath = indexDir.resolve("index.txt");
        indexer.save(indexPath.toString());

        Search search = new Search();
        InvertedIndex idx = indexer.getIndex();
        List<String> ranked = search.search(query, idx);

        Logger.info("Indexed words: " + idx.terms().size());
        Logger.info("Documents: " + idx.totalDocs());
        Logger.info("Query: " + query);

        int limit = Math.min(10, ranked.size());
        Logger.info("Top results (" + limit + "):");
        for (int i = 0; i < limit; i++) {
            Logger.info((i + 1) + ". " + ranked.get(i));
        }

        try (BufferedWriter out = Files.newBufferedWriter(
                indexDir.resolve("last_search.txt"),
                java.nio.charset.StandardCharsets.UTF_8)) {

            out.write("Query: " + query + "\n");
            for (int i = 0; i < limit; i++) {
                out.write((i + 1) + ". " + ranked.get(i) + "\n");
            }
        } catch (Exception e) {
            Logger.warn("Failed to write last_search.txt: " + e.getMessage());
        }
    }
}
