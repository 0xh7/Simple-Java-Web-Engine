import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class WebCrawlerGermany {
    private static final int MAX_PAGES = 200;

    private final Set<String> visitedLinks =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> discoveredHosts =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final PageDownloader downloader = new PageDownloader();
    private final LinkExtractor extractor = new SimpleLinkExtractor();
    private final Path pagesDir;
    private final Path indexDir;
    private final boolean multiThread;
    private final ExecutorService executor;
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final AtomicInteger pageCounter = new AtomicInteger(0);

    private volatile String baseDomain;

    public WebCrawlerGermany(Path pagesDir, Path indexDir, boolean multiThread) {
        this.pagesDir = pagesDir;
        this.indexDir = indexDir;
        this.multiThread = multiThread;

        try {
            java.nio.file.Files.createDirectories(this.pagesDir);
        } catch (Exception e) {
            Logger.error("Failed to create pages dir: " + e.getMessage(), e);
        }

        try {
            java.nio.file.Files.createDirectories(this.indexDir);
        } catch (Exception e) {
            Logger.error("Failed to create index dir: " + e.getMessage(), e);
        }

        if (multiThread) {
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
            this.executor = Executors.newFixedThreadPool(threads);
        } else {
            this.executor = null;
        }
    }

    public void crawl(String url, int depth) {
        if (depth <= 0 || url == null || url.isEmpty()) return;

        initBaseDomainIfNeeded(url);
        if (baseDomain == null) {
            Logger.warn("Invalid seed URL: " + url);
            if (executor != null) executor.shutdownNow();
            return;
        }

        if (multiThread) {
            crawlMulti(url, depth);

            while (pendingTasks.get() > 0) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (executor != null) {
                executor.shutdown();
                try {
                    executor.awaitTermination(60, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }

            Logger.info("Finished crawling. Total pages: " + visitedLinks.size());
        } else {
            crawlSingle(url, depth);
            Logger.info("Finished crawling. Total pages: " + visitedLinks.size());
        }
    }

    private void initBaseDomainIfNeeded(String url) {
        if (this.baseDomain != null) return;
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host != null && host.startsWith("www.")) {
                host = host.substring(4);
            }
            this.baseDomain = host;
        } catch (Exception e) {
            Logger.warn("Invalid seed URL: " + url);
        }
    }

    private static String normalizeUrl(String rawUrl) {
        if (rawUrl == null) return null;
        try {
            URI u = new URI(rawUrl);

            String scheme = (u.getScheme() == null)
                    ? "http"
                    : u.getScheme().toLowerCase(Locale.ROOT);

            String host = (u.getHost() == null)
                    ? ""
                    : u.getHost().toLowerCase(Locale.ROOT);

            int port = u.getPort();
            if ((scheme.equals("http") && port == 80) ||
                (scheme.equals("https") && port == 443)) {
                port = -1;
            }

            String path = u.getPath();
            if (path == null || path.isEmpty()) path = "/";

            String query = u.getQuery();

            URI norm = new URI(scheme, null, host, port, path, query, null);
            String s = norm.toString();
            if (s.endsWith("/") && !"/".equals(path)) {
                s = s.substring(0, s.length() - 1);
            }

            return s;
        } catch (Exception e) {
            return rawUrl;
        }
    }

    private boolean tryVisitAndReserve(String url) {
        if (pageCounter.get() >= MAX_PAGES) return false;

        boolean isNew = visitedLinks.add(url);
        if (!isNew) return false;

        int after = pageCounter.incrementAndGet();
        if (after > MAX_PAGES) {
            visitedLinks.remove(url);
            pageCounter.decrementAndGet();
            return false;
        }

        // عرض تقدم بسيط
        if (after % 10 == 0 || after == MAX_PAGES) {
            Logger.info("Visited " + after + " / " + MAX_PAGES + " pages...");
        }

        return true;
    }

    private void crawlSingle(String rawUrl, int depth) {
        if (depth <= 0) return;
        if (rawUrl == null || rawUrl.isEmpty()) return;

        String url = normalizeUrl(rawUrl);

        if (!isUrlInSameDomain(url)) return;
        if (!tryVisitAndReserve(url)) return;

        addHost(url);
        Logger.info("Crawling: " + url);

        String html = downloader.download(url);
        if (html == null || html.isEmpty()) return;

        savePage(url, html);

        List<String> links = extractor.extractLinks(html, url);
        Logger.info("Extracted links: " + links.size() + " from " + url);
        for (String link : links) {
            if (pageCounter.get() >= MAX_PAGES) break;

            String norm = normalizeUrl(link);

            // اسمح بروابط تنتهي بسلاش أو بدون امتداد، لكن امنع الملفات غير HTML (صور/فيديو/أرشيف..)
            if (Utils.isNonHtmlResource(norm) && !norm.endsWith("/")) continue;

            if (!isUrlInSameDomain(norm)) continue;

            crawlSingle(norm, depth - 1);
        }
    }

    private void crawlMulti(String seedUrl, int depth) {
        submitUrl(seedUrl, depth);
    }

    private void submitUrl(String rawUrl, int depth) {
        if (depth <= 0) return;
        if (rawUrl == null || rawUrl.isEmpty()) return;

        String url = normalizeUrl(rawUrl);

        if (!isUrlInSameDomain(url)) return;
        if (!tryVisitAndReserve(url)) return;

        pendingTasks.incrementAndGet();
        executor.submit(() -> {
            try {
                processUrl(url, depth);
            } finally {
                pendingTasks.decrementAndGet();
            }
        });
    }

    private void processUrl(String url, int depth) {
        addHost(url);
        Logger.info("Crawling: " + url);

        String html = downloader.download(url);
        if (html == null || html.isEmpty()) return;

        savePage(url, html);

        List<String> links = extractor.extractLinks(html, url);
        Logger.info("Extracted links: " + links.size() + " from " + url);
        for (String link : links) {
            if (pageCounter.get() >= MAX_PAGES) break;

            String norm = normalizeUrl(link);

            if (Utils.isNonHtmlResource(norm) && !norm.endsWith("/")) continue;

            submitUrl(norm, depth - 1); // isUrlInSameDomain سيتحقق داخل submitUrl
        }
    }

    private void addHost(String url) {
        try {
            URI u = new URI(url);
            String host = u.getHost();
            if (host != null) {
                if (host.startsWith("www.")) host = host.substring(4);
                if (isSameDomain(host)) {
                    discoveredHosts.add(host);
                }
            }
        } catch (Exception e) {
            Logger.debug("Failed to parse host from: " + url + " (" + e.getMessage() + ")");
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
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            return baseDomain != null && (host.equals(baseDomain) || host.endsWith("." + baseDomain));
        } catch (Exception e) {
            return false;
        }
    }

    private void savePage(String url, String content) {
        try {
            Path dir = this.pagesDir;
            String baseName = url.replaceFirst("https?://", "");
            baseName = Utils.sanitizeFileName(baseName);
            if (!baseName.endsWith(".html")) baseName += ".html";

            Path out = dir.resolve(baseName);
            if (java.nio.file.Files.exists(out)) {
                String hex = Utils.shortHex(url);
                int dot = baseName.lastIndexOf('.');
                String withHash = (dot > 0)
                        ? baseName.substring(0, dot) + "_" + hex + baseName.substring(dot)
                        : baseName + "_" + hex;
                out = dir.resolve(withHash);
            }

            Utils.writeAtomic(out, w -> {
                try {
                    w.write(content);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });

        } catch (Exception e) {
            Logger.error("Error saving page: " + e.getMessage(), e);
        }
    }

    public Set<String> getDiscoveredHosts() {
        return new HashSet<>(discoveredHosts);
    }

    public void saveDiscoveredHosts(String path) {
        try {
            Path p = Paths.get(path);
            Utils.ensureParentDirs(p);

            if (java.nio.file.Files.exists(p)) {
                String date = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
                Path backup = p.getParent().resolve("hosts_backup_" + date + ".txt");
                java.nio.file.Files.copy(p, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            Utils.writeAtomic(p, w -> {
                try {
                    for (String h : discoveredHosts) {
                        w.write(h);
                        w.write(System.lineSeparator());
                    }
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
        } catch (Exception e) {
            Logger.error("Error saving hosts: " + e.getMessage(), e);
        }
    }
}
