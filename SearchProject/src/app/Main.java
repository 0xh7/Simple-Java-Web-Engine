package app;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import search.Search;
import indexer.Indexer;
import indexer.InvertedIndex;
import util.Logger;

public class Main {
    private static class Config {
        final String seedUrl;
        final int depth;
        final String mode;   
        final String query;  

        Config(String seedUrl, int depth, String mode, String query) {
            this.seedUrl = seedUrl;
            this.depth = depth;
            this.mode = mode;
            this.query = query;
        }
    }

    public static void main(String[] args) {
        Logger.setLevel(Logger.Level.INFO);

        Config cfg = parseArgs(args);

        boolean multi = isMultiThreadMode(cfg.mode);

        String runId = timestampRunId();
        Path basePagesDir;
        Path baseIndexDir;
        {
            Dirs d = prepareDirs(runId);
            basePagesDir = d.pagesDir;
            baseIndexDir = d.indexDir;
        }

        WebCrawlerGermany crawler = crawlSite(cfg.seedUrl, cfg.depth, multi, basePagesDir);

        crawler.saveDiscoveredHosts(baseIndexDir.resolve("hosts.txt").toString());

        Logger.info("Discovered hosts:");
        for (String host : crawler.getDiscoveredHosts()) {
            Logger.info(" - " + host);
        }

        Indexer indexer = new Indexer();
        indexPages(indexer, basePagesDir);

        Path indexPath = baseIndexDir.resolve("index.txt");
        indexer.save(indexPath.toString());

        runSearchAndReport(indexer, cfg.query, baseIndexDir);
    }

    private static Config parseArgs(String[] args) {
        String seedUrl = "https://example.com";
        int depth = 1;
        String mode = "s";
        String query = "example";

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

        return new Config(seedUrl, depth, mode, query);
    }

    private static boolean isMultiThreadMode(String mode) {
        return mode.equalsIgnoreCase("m")
            || mode.equalsIgnoreCase("multi")
            || mode.equalsIgnoreCase("mt");
    }

    private static String timestampRunId() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
    }

    private static class Dirs {
        final Path pagesDir;
        final Path indexDir;
        Dirs(Path pagesDir, Path indexDir) {
            this.pagesDir = pagesDir;
            this.indexDir = indexDir;
        }
    }

    private static Dirs prepareDirs(String runId) {
        String baseDataDir = System.getenv().getOrDefault("CRAWLER_DATA_DIR", "data");

        Path pagesDir = Path.of(baseDataDir, "pages", runId);
        Path indexDir = Path.of(baseDataDir, "index", runId);

        try {
            Files.createDirectories(pagesDir);
        } catch (Exception e) {
            Logger.error("Failed to ensure pagesDir exists: " + e.getMessage(), e);
        }

        try {
            Files.createDirectories(indexDir);
        } catch (Exception e) {
            Logger.error("Failed to ensure indexDir exists: " + e.getMessage(), e);
        }

        return new Dirs(pagesDir, indexDir);
    }

    private static WebCrawlerGermany crawlSite(String seedUrl,
                                               int depth,
                                               boolean multi,
                                               Path pagesDir) {

        WebCrawlerGermany crawler = new WebCrawlerGermany(pagesDir, multi);
        crawler.crawl(seedUrl, depth);
        return crawler;
    }

    private static void indexPages(Indexer indexer, Path pagesDir) {
        try {
            java.io.File[] pageFiles = pagesDir.toFile().listFiles();

            if (pageFiles == null) {
                Logger.warn("Pages directory is missing or not a directory: " + pagesDir);
                return;
            }

            for (java.io.File file : pageFiles) {
                try {
                    if (!file.isFile()) {
                        continue;
                    }

                    String nameLower = file.getName().toLowerCase(Locale.ROOT);
                    boolean isHtml = nameLower.endsWith(".html") || nameLower.endsWith(".htm");
                    if (!isHtml) {
                        continue;
                    }

                    StringBuilder sb = new StringBuilder(4096);
                    try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                    }

                    indexer.addPage(file.getName(), sb.toString());

                } catch (Exception e) {
                    Logger.warn("Error reading page file " + file.getName() + ": " + e.getMessage());
                }
            }

        } catch (Exception e) {
            Logger.error("Error scanning pages directory: " + e.getMessage(), e);
        }
    }

    private static void runSearchAndReport(Indexer indexer,
                                           String query,
                                           Path indexDir) {

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

        writeLastSearchFile(indexDir, query, ranked, limit);
    }

    private static void writeLastSearchFile(Path indexDir,
                                            String query,
                                            List<String> ranked,
                                            int limit) {
        Path outFile = indexDir.resolve("last_search.txt");

        try (BufferedWriter out = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            out.write("Query: " + query + "\n");
            for (int i = 0; i < limit; i++) {
                out.write((i + 1) + ". " + ranked.get(i) + "\n");
            }
        } catch (IOException e) {
            Logger.error("Failed to write last_search.txt: " + e.getMessage(), e);
        }
    }
}
