import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WebCrawlerGermany {
    private static final int MAX_PAGES = 200;

    private final Set<String> visitedLinks = new HashSet<>();
    private final Set<String> discoveredHosts = new HashSet<>();
    private final PageDownloader downloader = new PageDownloader();
    private final LinkExtractor extractor = new SimpleLinkExtractor();
    private String baseDomain;

    public void crawl(String url, int depth) {
        if (depth <= 0) return;
        if (visitedLinks.contains(url)) return;
        if (visitedLinks.size() >= MAX_PAGES) return;

      
        if (baseDomain == null) {
            try {
                URI uri = new URI(url);
                baseDomain = uri.getHost();
                if (baseDomain != null && baseDomain.startsWith("www.")) {
                    baseDomain = baseDomain.substring(4);
                }
            } catch (Exception e) {
                return;
            }
        }


        if (!isUrlInSameDomain(url)) {
            return;
        }

        visitedLinks.add(url);

        
        try {
            URI u = new URI(url);
            String host = u.getHost();
            if (host != null) {
                if (host.startsWith("www.")) host = host.substring(4);
                if (isSameDomain(host)) {
                    discoveredHosts.add(host);
                }
            }
        } catch (Exception ignored) {}

        System.out.println("Crawling: " + url);

        String html = downloader.download(url);
        if (html == null || html.isEmpty()) return;

        savePage(url, html);

        List<String> links = extractor.extractLinks(html, url);
        for (String link : links) {
            if (visitedLinks.contains(link)) continue;
            if (visitedLinks.size() >= MAX_PAGES) break;
            if (link.matches(".*\\.(css|js|png|jpg|jpeg|gif|svg|ico|pdf|webp|mp4|avi)$")) continue;

            try {
                URI linkUri = new URI(link);
                String host = linkUri.getHost();
                if (host == null) continue;
                if (host.startsWith("www.")) host = host.substring(4);
                if (!isSameDomain(host)) {
                    continue;
                }

                discoveredHosts.add(host);
            } catch (Exception e) {
                continue;
            }
            crawl(link, depth - 1);
        }

        if (depth == 1) {
            System.out.println("Finished crawling. Total pages: " + visitedLinks.size());
        }
    }

    private boolean isSameDomain(String host) {
        if (host == null || baseDomain == null) return false;
       
        return host.equals(baseDomain) || host.endsWith("." + baseDomain);
    } 
    private boolean isUrlInSameDomain(String fullUrl) {
        try {
            URI uri = new URI(fullUrl);
            String host = uri.getHost();
            if (host == null) return false;
            if (host.startsWith("www.")) host = host.substring(4);
            return isSameDomain(host);
        } catch (Exception e) {
            return false;
        }
    }

    private void savePage(String url, String content) {
        try {
            File dir = new File("data/pages");
            dir.mkdirs();
            String fileName = url.replaceFirst("https?://", "")
                    .replaceAll("[^a-zA-Z0-9.-]", "_");
            if (!fileName.endsWith(".html")) fileName += ".html";
            File outFile = new File(dir, fileName);
            try (FileWriter writer = new FileWriter(outFile)) {
                writer.write(content);
            }
        } catch (Exception e) {
            System.out.println("Error saving page: " + e.getMessage());
        }
    }

    public Set<String> getDiscoveredHosts() {
        return new HashSet<>(discoveredHosts);
    }

    public void saveDiscoveredHosts(String path) {
        try {
            File dir = new File(path).getParentFile();
            if (dir != null) dir.mkdirs();
            try (FileWriter w = new FileWriter(path)) {
                for (String h : discoveredHosts) {
                    w.write(h + System.lineSeparator());
                }
            }
        } catch (Exception e) {
            System.out.println("Error saving hosts: " + e.getMessage());
        }
    }
}
