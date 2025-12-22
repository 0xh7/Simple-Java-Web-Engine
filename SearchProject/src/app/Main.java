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
import java.util.Map;
import java.util.Locale;
import java.util.LinkedHashMap;

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

        ZeroXBot crawler = crawlSite(cfg.seedUrl, cfg.depth, multi, basePagesDir);

        crawler.saveDiscoveredHosts(baseIndexDir.resolve("hosts.txt").toString());
        Map<String, Double> pageRank = crawler.computePageRank();
        crawler.saveManifest(baseIndexDir.resolve("manifest.json"), pageRank);
        writeFetchLog(basePagesDir, baseIndexDir.resolve("fetch_log.jsonl"), crawler.getPageMetadata());

        Logger.info("Discovered hosts:");
        for (String host : crawler.getDiscoveredHosts()) {
            Logger.info(" - " + host);
        }

        Indexer indexer = new Indexer();
        indexPages(indexer, basePagesDir, crawler.getPageMetadata(), crawler.getInlinkCounts(), pageRank, crawler.getAnchorTextByFile());

        Path indexPath = baseIndexDir.resolve("index.txt");
        indexer.save(indexPath.toString());

        runSearchAndReport(indexer, cfg.query, baseIndexDir, basePagesDir);
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

    private static ZeroXBot crawlSite(String seedUrl,
                                      int depth,
                                      boolean multi,
                                      Path pagesDir) {

        ZeroXBot crawler = new ZeroXBot(pagesDir, multi);
        crawler.crawl(seedUrl, depth);
        return crawler;
    }

    private static void indexPages(Indexer indexer,
                                   Path pagesDir,
                                   Map<String, ZeroXBot.PageMeta> meta,
                                   Map<String, Integer> inlinkCounts,
                                   Map<String, Double> pageRank,
                                   Map<String, java.util.List<String>> crawlerAnchors) {
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

                    ZeroXBot.PageMeta pm = meta.get(file.getName());
                    String url = pm != null ? pm.url : file.getName();
                    String canonical = pm != null ? pm.canonicalUrl : url;
                    long fetchedAt = pm != null ? pm.fetchedAt : System.currentTimeMillis();
                    int inlinks = inlinkCounts.getOrDefault(file.getName(), 0);
                    double pr = pageRank.getOrDefault(file.getName(), 0.0);

                    java.util.List<String> anchors = null;
                    if (crawlerAnchors != null) {
                        anchors = crawlerAnchors.get(file.getName());
                    }
                    indexer.addPage(file.getName(), url, canonical, sb.toString(), inlinks, fetchedAt, pr, anchors);

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
                                           Path indexDir,
                                           Path pagesDir) {

        Search search = new Search();
        InvertedIndex idx = indexer.getIndex();
        List<Search.SearchResult> ranked = search.searchDetailed(query, idx, indexer.getMetadata());
        String suggestion = search.suggest(query, idx);
        List<Search.SearchResult> suggestedRanked = List.of();
        int suggestionLimit = 0;
        if (suggestion != null && !suggestion.isBlank()
                && !suggestion.equalsIgnoreCase(query)) {
            suggestedRanked = search.searchDetailed(suggestion, idx, indexer.getMetadata());
            suggestionLimit = Math.min(10, suggestedRanked.size());
        }

        Logger.info("Indexed words: " + idx.terms().size());
        Logger.info("Documents: " + idx.totalDocs());
        Logger.info("Query: " + query);
        if (suggestion != null && !suggestion.isBlank()
                && !suggestion.equalsIgnoreCase(query)) {
            Logger.info("Did you mean: " + suggestion + " ?");
        }

        int limit = Math.min(10, ranked.size());

        Logger.info("Top results (" + limit + "):");
        for (int i = 0; i < limit; i++) {
            Search.SearchResult r = ranked.get(i);
            String title = (r.title() != null && !r.title().isBlank()) ? r.title() : r.document();
            String url = (r.url() != null && !r.url().isBlank()) ? r.url() : r.document();
            Logger.info((i + 1) + ". " + title + " [" + url + "] " + String.format(Locale.ROOT, "(%.4f)", r.score()));
            if (r.snippet() != null && !r.snippet().isBlank()) {
                Logger.info("    " + r.snippet());
            }
        }

        writeLastSearchFile(indexDir, query, ranked, limit);
        writeHtmlResults(indexDir, pagesDir, query, suggestion, ranked, limit, suggestedRanked, suggestionLimit);
    }

    private static void writeLastSearchFile(Path indexDir,
                                            String query,
                                            List<Search.SearchResult> ranked,
                                            int limit) {
        Path outFile = indexDir.resolve("last_search.txt");

        try (BufferedWriter out = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            out.write("Query: " + query + "\n");
            for (int i = 0; i < limit; i++) {
                Search.SearchResult r = ranked.get(i);
                out.write((i + 1) + ". " + r.document());
                out.write(" | score=");
                out.write(String.format(Locale.ROOT, "%.4f", r.score()));
                if (r.url() != null && !r.url().isBlank()) {
                    out.write(" | url=");
                    out.write(r.url());
                }
                if (r.title() != null && !r.title().isBlank()) {
                    out.write(" | title=");
                    out.write(r.title());
                }
                if (r.snippet() != null && !r.snippet().isBlank()) {
                    out.write(" | snippet=");
                    out.write(r.snippet());
                }
                out.write("\n");
            }
        } catch (IOException e) {
            Logger.error("Failed to write last_search.txt: " + e.getMessage(), e);
        }
    }

    private static void writeFetchLog(Path pagesDir,
                                      Path logPath,
                                      Map<String, ZeroXBot.PageMeta> meta) {
        try {
            Map<String, Long> sizes = new LinkedHashMap<>();
            java.io.File[] pageFiles = pagesDir.toFile().listFiles();
            if (pageFiles != null) {
                for (java.io.File f : pageFiles) {
                    if (f.isFile()) {
                        sizes.put(f.getName(), f.length());
                    }
                }
            }

            Files.createDirectories(logPath.getParent());
            try (BufferedWriter out = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, ZeroXBot.PageMeta> e : meta.entrySet()) {
                    ZeroXBot.PageMeta pm = e.getValue();
                    long size = sizes.getOrDefault(pm.fileName, 0L);
                    out.write("{\"file\":\"");
                    out.write(escape(pm.fileName));
                    out.write("\",\"url\":\"");
                    out.write(escape(pm.url));
                    out.write("\",\"canonical\":\"");
                    out.write(escape(pm.canonicalUrl));
                    out.write("\",\"fetchedAt\":");
                    out.write(String.valueOf(pm.fetchedAt));
                    out.write(",\"sizeBytes\":");
                    out.write(String.valueOf(size));
                    out.write("}\n");
                }
            }
        } catch (Exception e) {
            Logger.warn("Failed to write fetch_log.jsonl: " + e.getMessage());
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void writeHtmlResults(Path indexDir,
                                         Path pagesDir,
                                         String query,
                                         String suggestion,
                                         List<Search.SearchResult> ranked,
                                         int limit,
                                         List<Search.SearchResult> suggested,
                                         int suggestionLimit) {
        Path out = indexDir.resolve("results.html");
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>0x Results</title>");
            w.write("<style>");
            w.write("body{font-family:Arial, sans-serif;background:#0f172a;color:#e2e8f0;padding:20px;}");
            w.write(".wrap{max-width:860px;margin:0 auto;}");
            w.write(".card{background:#111827;border:1px solid #1f2937;border-radius:10px;padding:12px 14px;margin:0 0 10px;box-shadow:0 6px 18px rgba(0,0,0,0.25);}");
            w.write(".title{margin:0;font-size:17px;font-weight:700;color:#93c5fd;}");
            w.write(".url{font-size:12px;color:#9ca3af;margin-top:4px;}");
            w.write(".snippet{font-size:13px;color:#e5e7eb;margin-top:6px;line-height:1.4;}");
            w.write(".meta{font-size:12px;color:#94a3b8;margin:10px 0;}");
            w.write(".suggest{color:#60a5fa;font-weight:700;}");
            w.write("a{color:#93c5fd;text-decoration:none;}a:hover{text-decoration:underline;}");
            w.write("</style></head><body><div class='wrap'>");
            w.write("<h2 style='margin-top:0'>0x Search</h2>");
            w.write("<div class='meta'>Query: " + htmlEscape(query) + " | Showing " + limit + " of " + ranked.size() + "</div>");
            if (suggestion != null && !suggestion.isBlank() && !suggestion.equalsIgnoreCase(query)) {
                w.write("<div class='meta'>Did you mean: <a id='suggest-link' class='suggest' href='#'>" + htmlEscape(suggestion) + "</a> ?</div>");
            }
            w.write("<div id='results-current'>");
            for (int i = 0; i < limit; i++) {
                Search.SearchResult r = ranked.get(i);
                String title = (r.title() != null && !r.title().isBlank()) ? r.title() : r.document();
                String url = (r.url() != null && !r.url().isBlank()) ? r.url() : r.document();
                String local = pagesDir.resolve(r.document()).toUri().toString();
                w.write("<div class='card'>");
                w.write("<div class='title'><a href=\"" + htmlEscape(url) + "\" target=\"_blank\" rel=\"noreferrer\">" + htmlEscape(title) + "</a></div>");
                w.write("<div class='url'>");
                w.write("<a href=\"" + htmlEscape(url) + "\" target=\"_blank\" rel=\"noreferrer\">" + htmlEscape(url) + "</a>");
                w.write(" | <a href=\"" + htmlEscape(local) + "\" target=\"_blank\" rel=\"noreferrer\">local copy</a>");
                w.write(" | score " + String.format(Locale.ROOT, "%.4f", r.score()));
                w.write("</div>");
                if (r.snippet() != null && !r.snippet().isBlank()) {
                    w.write("<div class='snippet'>" + htmlEscape(r.snippet()) + "</div>");
                }
                w.write("</div>");
            }
            w.write("</div>");
            if (suggestion != null && !suggestion.isBlank() && !suggestion.equalsIgnoreCase(query) && suggestionLimit > 0) {
                w.write("<div id='results-suggested' style='display:none;'>");
                w.write("<div class='meta'>Showing suggestion: " + htmlEscape(suggestion) + " (" + suggestionLimit + " of " + suggested.size() + ")</div>");
                for (int i = 0; i < suggestionLimit; i++) {
                    Search.SearchResult r = suggested.get(i);
                    String title = (r.title() != null && !r.title().isBlank()) ? r.title() : r.document();
                    String url = (r.url() != null && !r.url().isBlank()) ? r.url() : r.document();
                    String local = pagesDir.resolve(r.document()).toUri().toString();
                    w.write("<div class='card'>");
                    w.write("<div class='title'><a href=\"" + htmlEscape(url) + "\" target=\"_blank\" rel=\"noreferrer\">" + htmlEscape(title) + "</a></div>");
                    w.write("<div class='url'>");
                    w.write("<a href=\"" + htmlEscape(url) + "\" target=\"_blank\" rel=\"noreferrer\">" + htmlEscape(url) + "</a>");
                    w.write(" | <a href=\"" + htmlEscape(local) + "\" target=\"_blank\" rel=\"noreferrer\">local copy</a>");
                    w.write(" | score " + String.format(Locale.ROOT, "%.4f", r.score()));
                    w.write("</div>");
                    if (r.snippet() != null && !r.snippet().isBlank()) {
                        w.write("<div class='snippet'>" + htmlEscape(r.snippet()) + "</div>");
                    }
                    w.write("</div>");
                }
                w.write("</div>");
                w.write("<script>const link=document.getElementById('suggest-link');if(link){link.addEventListener('click',function(e){e.preventDefault();document.getElementById('results-current').style.display='none';const s=document.getElementById('results-suggested');if(s){s.style.display='block';}});}</script>");
            }
            w.write("</div></body></html>");
        } catch (Exception e) {
            Logger.warn("Failed to write results.html: " + e.getMessage());
        }
    }

    private static String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
