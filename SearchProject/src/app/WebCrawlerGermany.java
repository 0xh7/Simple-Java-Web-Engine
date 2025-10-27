package app;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import crawler.PageDownloader;
import crawler.LinkExtractor;
import crawler.SimpleLinkExtractor;
import util.Utils;
import util.Logger;

public class WebCrawlerGermany {

    private static final int MAX_PAGES = 200;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger(1);

    private final Set<String> visitedLinks =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final Set<String> discoveredHosts =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final PageDownloader downloader = new PageDownloader();
    private final LinkExtractor extractor = new SimpleLinkExtractor();

    private final Path pagesDir;
    private final boolean multiThread;

    private final ExecutorService executor;
    private final Phaser phaser;

    private final AtomicInteger pageCounter = new AtomicInteger(0);

    private volatile String baseDomain;

    public WebCrawlerGermany(Path pagesDir, boolean multiThread) {
        this.pagesDir = pagesDir;
        this.multiThread = multiThread;

        try {
            java.nio.file.Files.createDirectories(this.pagesDir);
        } catch (Exception e) {
            Logger.error("Failed to create pages dir: " + e.getMessage(), e);
        }

        if (multiThread) {
            int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
            int defaultThreads = Math.max(2, cpus * 2);

            int threads = defaultThreads;
            try {
                threads = Integer.parseInt(System.getProperty("crawler.threads", String.valueOf(defaultThreads)));
            } catch (NumberFormatException ignore) {
            }

            this.executor = Executors.newFixedThreadPool(
                    threads,
                    r -> {
                        Thread t = new Thread(r, "crawler-" + THREAD_SEQ.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
            );

            this.phaser = new Phaser(1);
        } else {
            this.executor = null;
            this.phaser = null;
        }
    }

    public void crawl(String url, int depth) {
        if (depth <= 0 || url == null || url.isEmpty()) {
            Logger.warn("Invalid crawl request (url=" + url + ", depth=" + depth + ")");
            return;
        }

        initBaseDomainIfNeeded(url);
        if (baseDomain == null) {
            Logger.warn("Invalid seed URL (baseDomain unresolved): " + url);
            if (executor != null) executor.shutdownNow();
            return;
        }

        if (multiThread) {
            crawlMulti(url, depth);

            try {
                phaser.arriveAndAwaitAdvance();
            } catch (Exception e) {
                Logger.warn("Interrupted while waiting for tasks: " + e.getMessage());
                Thread.currentThread().interrupt();
            } finally {
                try {
                    phaser.arriveAndDeregister();
                } catch (Exception ignore) {
                }
            }

            if (executor != null) {
                executor.shutdown();
                try {
                    executor.awaitTermination(60, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }

            Logger.info("Finished crawling. Total pages: " + pageCounter.get());

        } else {
            crawlSingle(url, depth);
            Logger.info("Finished crawling. Total pages: " + pageCounter.get());
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
            if (u.getHost() == null) {
                return rawUrl;
            }

            String scheme = (u.getScheme() == null)
                    ? "http"
                    : u.getScheme().toLowerCase(Locale.ROOT);

            String host = u.getHost().toLowerCase(Locale.ROOT);

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
      
        if (!visitedLinks.add(url)) {
            return false;
        }

        int after;
        while (true) {
            int curr = pageCounter.get();

            if (curr >= MAX_PAGES) {
                 
                visitedLinks.remove(url);
                return false;
            }

          
            if (pageCounter.compareAndSet(curr, curr + 1)) {
                after = curr + 1;
                break;
            }

         
        }

        if (after % 10 == 0 || after == MAX_PAGES) {
            Logger.info("Visited " + after + " / " + MAX_PAGES + " pages...");
        }

        return true;
    }

    private void crawlSingle(String rawUrl, int depth) {
        if (depth <= 0 || rawUrl == null || rawUrl.isEmpty()) return;

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

            if (Utils.isNonHtmlResource(norm) && !norm.endsWith("/")) continue;

            if (!isUrlInSameDomain(norm)) continue;

            crawlSingle(norm, depth - 1);
        }
    }

    private void crawlMulti(String seedUrl, int depth) {
        submitUrl(seedUrl, depth);
    }

    private void submitUrl(String rawUrl, int depth) {
        if (depth <= 0 || rawUrl == null || rawUrl.isEmpty()) return;

        final String url = normalizeUrl(rawUrl);
        if (!isUrlInSameDomain(url)) return;
        if (!tryVisitAndReserve(url)) return;

        Runnable task = () -> {
            try {
                processUrl(url, depth);
            } catch (Exception e) {
                Logger.error("Unhandled error in task for " + url + ": " + e.getMessage(), e);
            } finally {
                try {
                    phaser.arriveAndDeregister();
                } catch (Exception ignore) {
                }
            }
        };

        phaser.register();
        try {
            executor.submit(task);
        } catch (RejectedExecutionException rex) {
            try {
                phaser.arriveAndDeregister();
            } catch (Exception ignore) {
            }
            Logger.warn("Task rejected for url " + url + ": " + rex.getMessage());
        }
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

            if (!isUrlInSameDomain(norm)) continue;

            if (depth - 1 > 0) {
                submitUrl(norm, depth - 1);
            }
        }
    }

    private void addHost(String url) {
        try {
            URI u = new URI(url);
            String host = u.getHost();
            if (host != null) {
                if (host.startsWith("www.")) host = host.substring(4);
                host = host.toLowerCase(Locale.ROOT);
                if (isSameDomain(host)) {
                    discoveredHosts.add(host);
                }
            }
        } catch (Exception e) {
            Logger.debug("Failed to parse host from: " + url + " (" + e.getMessage() + ")");
        }
    }

    private boolean isUrlInSameDomain(String fullUrl) {
        try {
            URI uri = new URI(fullUrl);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            return isSameDomain(host);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSameDomain(String host) {
        if (host == null || baseDomain == null) return false;
        return host.equals(baseDomain) || host.endsWith("." + baseDomain);
    }

    private void savePage(String url, String content) {
        try {
            Path dir = this.pagesDir;

            String baseName = url.replaceFirst("https?://(www\\.)?", "");
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
        return Collections.unmodifiableSet(new HashSet<>(discoveredHosts));
    }

    public void saveDiscoveredHosts(String path) {
        try {
            Path p = Paths.get(path);
            Utils.ensureParentDirs(p);

            if (java.nio.file.Files.exists(p)) {
                String date = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                        .format(new java.util.Date());
                Path backup = p.getParent().resolve("hosts_backup_" + date + ".txt");
                java.nio.file.Files.copy(
                        p,
                        backup,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
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
