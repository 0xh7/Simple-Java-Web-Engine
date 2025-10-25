import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.*;

public class Main {
    public static void main(String[] args) {
      
        String seedUrl = "https://example.com";
        int depth = 1;
        String query = "example";

        if (args.length >= 1) seedUrl = args[0];
        if (args.length >= 2) {
            try {
                depth = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {}
        }
        if (args.length >= 3) query = args[2];

        WebCrawlerGermany crawler = new WebCrawlerGermany();
        crawler.crawl(seedUrl, depth);

        crawler.saveDiscoveredHosts("data/index/hosts.txt");
        System.out.println("\nDiscovered hosts:");
        for (String host : crawler.getDiscoveredHosts()) {
            System.out.println(" - " + host);
        }

        Indexer indexer = new Indexer();
        File pagesDir = new File("data/pages");
        pagesDir.mkdirs();
        File[] pageFiles = pagesDir.listFiles();
        if (pageFiles != null) {
            for (File file : pageFiles) {
                try {
                    if (!file.isFile()) continue;
                    String name = file.getName().toLowerCase(Locale.ROOT);
                    if (!(name.endsWith(".html") || name.endsWith(".htm"))) continue;
                    String content = Files.readString(file.toPath());
                    indexer.addPage(file.getName(), content);
                } catch (Exception e) {
                    System.out.println("Error reading page file: " + e.getMessage());
                }
            }
        }

       
        File indexDir = new File("data/index");
        indexDir.mkdirs();
        indexer.save("data/index/index.txt");

       
        Map<String, Map<String, Integer>> idx = indexer.getIndex();
        Search search = new Search();
        List<String> ranked = search.search(query, idx);

        System.out.println("\nIndexed words: " + idx.size());
        System.out.println("Query: " + query);
        int limit = Math.min(10, ranked.size());
        System.out.println("Top results (" + limit + "):");
        for (int i = 0; i < limit; i++) {
            System.out.println((i + 1) + ". " + ranked.get(i));
        }

        try (FileWriter out = new FileWriter("data/index/last_search.txt")) {
            out.write("Query: " + query + "\n");
            for (int i = 0; i < limit; i++) {
                out.write((i + 1) + ". " + ranked.get(i) + "\n");
            }
        } catch (Exception ignored) {}
    }
}
