package app;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import crawler.PageDownloader;
import crawler.LinkExtractor;
import crawler.SimpleLinkExtractor;
import util.Utils;
import util.Logger;

public class ZeroXBot {

    public static final String BOT_NAME = "0xBot";
    private static final int MAX_PAGES = 200;
    private static final long MIN_DELAY_MS = 400;
    private static final int SITEMAP_LIMIT = 500;
    private static final int SITEMAP_FETCH_LIMIT = 100;
    private static final int HASH_CACHE_SIZE = 5000;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger(1);

    private final Set<String> visitedLinks =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final Set<String> discoveredHosts =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final Set<String> contentHashes =
            Collections.newSetFromMap(Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(HASH_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > HASH_CACHE_SIZE;
                }
            }));

    private final ConcurrentMap<String, Long> hostLastFetch =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, HostQueue> hostQueues =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> hostLocks =
            new ConcurrentHashMap<>();
    private final Map<String, String> urlHashes =
            Collections.synchronizedMap(new LinkedHashMap<>(HASH_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > HASH_CACHE_SIZE;
                }
            });

    private final ConcurrentMap<String, PageMeta> pageMetadata =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Set<String>> linkGraph =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<String, String> urlToFile =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<String, java.util.List<String>> anchorTexts =
            new ConcurrentHashMap<>();

    private final PageDownloader downloader = new PageDownloader();
    private final LinkExtractor extractor = new SimpleLinkExtractor();

    private final Path pagesDir;
    private final boolean multiThread;

    private final ExecutorService executor;
    private final PriorityBlockingQueue<FetchTask> frontier =
            new PriorityBlockingQueue<>();
    private final AtomicBoolean frontierWorkerRunning = new AtomicBoolean(false);
    private final AtomicLong frontierSeq = new AtomicLong(0);
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final Object pendingTasksMonitor = new Object();

    private final AtomicInteger pageCounter = new AtomicInteger(0);

    private volatile String baseDomain;

    private static final Pattern CANONICAL_PATTERN =
            Pattern.compile("(?is)<link[^>]+rel\\s*=\\s*\"?canonical\"?[^>]*href\\s*=\\s*\"([^\"]+)\"");

    private static final Pattern SITEMAP_LOC_PATTERN =
            Pattern.compile("(?is)<loc>\\s*([^<]+)\\s*</loc>");

    private static final Pattern ANCHOR_PATTERN =
            Pattern.compile("(?is)<a[^>]*href\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))[^>]*>(.*?)</a>");

    private static final class FetchTask implements Comparable<FetchTask> {
        final String url;
        final int depth;
        final double priority;
        final long seq;
        FetchTask(String url, int depth, double priority, long seq) {
            this.url = url;
            this.depth = depth;
            this.priority = priority;
            this.seq = seq;
        }

        @Override
        public int compareTo(FetchTask o) {
            int c = Double.compare(o.priority, this.priority);
            if (c != 0) return c;
            return Long.compare(this.seq, o.seq);
        }
    }

    private static final class HostQueue {
        final java.util.concurrent.BlockingQueue<FetchTask> queue = new java.util.concurrent.LinkedBlockingQueue<>();
        volatile boolean workerRunning = false;
    }

    public ZeroXBot(Path pagesDir, boolean multiThread) {
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
        } else {
            this.executor = null;
        }
    }

    public void crawl(String url, int depth) {
        if (depth <= 0 || url == null || url.isEmpty()) {
            Logger.warn("Invalid crawl request (url=" + url + ", depth=" + depth + ")");
            shutdownExecutor();
            downloader.shutdown();
            return;
        }

        initBaseDomainIfNeeded(url);
        if (baseDomain == null) {
            Logger.warn("Invalid seed URL (baseDomain unresolved): " + url);
            if (executor != null) executor.shutdownNow();
            downloader.shutdown();
            return;
        }

        seedFromSitemap(url, depth);

        if (multiThread) {
            crawlMulti(url, depth);

            try {
                waitForPendingTasks();
            } catch (Exception e) {
                Logger.warn("Interrupted while waiting for tasks: " + e.getMessage());
                Thread.currentThread().interrupt();
            }

            shutdownExecutor();

            Logger.info("Finished crawling. Total pages: " + pageCounter.get());

        } else {
            crawlSingle(url, depth);
            Logger.info("Finished crawling. Total pages: " + pageCounter.get());
        }
        downloader.shutdown();
    }

    private void taskScheduled() {
        pendingTasks.incrementAndGet();
    }

    private void taskDone() {
        if (pendingTasks.decrementAndGet() <= 0) {
            synchronized (pendingTasksMonitor) {
                pendingTasksMonitor.notifyAll();
            }
        }
    }

    private void waitForPendingTasks() {
        while (pendingTasks.get() > 0) {
            synchronized (pendingTasksMonitor) {
                try {
                    pendingTasksMonitor.wait(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void shutdownExecutor() {
        if (executor == null) return;
        executor.shutdown();
        try {
            executor.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
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

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "utm_source","utm_medium","utm_campaign","utm_term","utm_content",
            "gclid","fbclid","msclkid","igshid","mc_eid","ref","ref_src","source"
    );

    private static final Set<String> SESSION_PARAMS = Set.of(
            "jsessionid","phpsessid","sid","sessionid","aspsessionid","cfid","cftoken"
    );

    private static String normalizeUrl(String rawUrl) {
        if (rawUrl == null) return null;
        try {
            URI u = new URI(rawUrl.trim());
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
            path = path.toLowerCase(Locale.ROOT);
            path = path.replaceAll("/{2,}", "/");
            if (path.endsWith("/index.html") || path.endsWith("/index.htm") || path.endsWith("/index.php")) {
                path = path.substring(0, path.lastIndexOf("/index"));
                if (path.isEmpty()) path = "/";
            }
            path = stripPathParams(path);

            String query = normalizeQuery(u.getRawQuery());

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

    private static String stripPathParams(String path) {
       
        return path.replaceAll(";(" + String.join("|", SESSION_PARAMS) + ")=[^/]*", "");
    }

    private static String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return null;
        String[] parts = rawQuery.split("&");
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            String name = (eq >= 0) ? p.substring(0, eq) : p;
            String lower = name.toLowerCase(Locale.ROOT);
            if (TRACKING_PARAMS.contains(lower) || lower.startsWith("utm_")) {
                continue;
            }
            if (SESSION_PARAMS.contains(lower)) {
                continue;
            }
            kept.add(p);
        }
        if (kept.isEmpty()) return null;
        kept.sort(String::compareTo);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) sb.append('&');
            sb.append(kept.get(i));
        }
        return sb.toString();
    }

    private Map<String, String> extractAnchors(String html, String baseUrl) {
        Map<String, String> out = new java.util.HashMap<>();
        if (html == null || html.isEmpty() || baseUrl == null || baseUrl.isEmpty()) return out;
        URI base;
        try {
            base = new URI(baseUrl);
        } catch (Exception e) {
            return out;
        }

        Matcher m = ANCHOR_PATTERN.matcher(html);
        while (m.find()) {
            String raw = firstNonNull(m.group(2), m.group(3), m.group(4));
            String text = m.group(5);
            if (raw == null || raw.isBlank()) continue;
            if (text == null || text.isBlank()) continue;

            try {
                URI resolved = base.resolve(raw);
                String abs = resolved.toString();
                String norm = normalizeUrl(abs);
                if (norm == null) continue;
                if (!isUrlInSameDomain(norm)) continue;
                if (Utils.isNonHtmlResource(norm) && !norm.endsWith("/")) continue;

                String cleaned = text.replaceAll("(?is)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                if (cleaned.isEmpty()) continue;
                out.put(norm, cleaned);
            } catch (Exception ignore) {
            }
        }
        return out;
    }

    private static String firstNonNull(String... vals) {
        for (String v : vals) {
            if (v != null) return v;
        }
        return null;
    }
    private void seedFromSitemap(String seedUrl, int depth) {
        try {
            URI u = new URI(seedUrl);
            String base = u.getScheme() + "://" + u.getHost();
            String sitemapUrl = base + "/sitemap.xml";

            Set<String> visitedSitemaps = ConcurrentHashMap.newKeySet();
            java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
            queue.add(sitemapUrl);

            int seeded = 0;
            int fetched = 0;
            while (!queue.isEmpty() && seeded < SITEMAP_LIMIT && fetched < SITEMAP_FETCH_LIMIT) {
                String current = queue.poll();
                if (!visitedSitemaps.add(current)) continue;

                String xml = downloader.downloadAllowingXml(current);
                fetched++;
                if (xml == null || xml.isBlank()) continue;

                boolean isIndex = xml.toLowerCase(Locale.ROOT).contains("<sitemapindex");
                Matcher m = SITEMAP_LOC_PATTERN.matcher(xml);
                while (m.find()) {
                    String loc = normalizeUrl(m.group(1));
                    if (loc == null || loc.isBlank()) continue;
                    if (!isUrlInSameDomain(loc)) continue;
                    if (isIndex) {
                        queue.add(loc);
                    } else if (seeded < SITEMAP_LIMIT) {
                        if (multiThread) {
                            submitUrl(loc, depth);
                        } else {
                            crawlSingle(loc, depth);
                        }
                        seeded++;
                    }
                    if (seeded >= SITEMAP_LIMIT) break;
                }
            }

            if (seeded > 0) {
                Logger.info("Seeded " + seeded + " URLs from sitemap(s)");
            }
        } catch (Exception e) {
            Logger.debug("failed to seed sitemap: " + e.getMessage());
        }
    }
    private boolean tryVisitAndReserve(String url) {
        int after;
        while (true) {
            int curr = pageCounter.get();
            if (curr >= MAX_PAGES) {
                return false;
            }
            if (pageCounter.compareAndSet(curr, curr + 1)) {
                after = curr + 1;
                break;
            }
        }

        if (!visitedLinks.add(url)) {
            pageCounter.decrementAndGet();
            return false;
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

        politeDelay(url);
        String html = downloader.downloadHybrid(url);
        if (html == null || html.isEmpty()) return;

        String canonical = extractCanonicalUrl(html, url);
        if (shouldSkipByHash(url, canonical, html)) return;
        if (canonical != null && !canonical.isBlank()) {
            visitedLinks.add(canonical);
        }
        savePage(url, canonical, html);

        String source = (canonical != null && !canonical.isBlank()) ? canonical : url;
        List<String> links = extractor.extractLinks(html, url);
        Map<String, String> anchorMap = extractAnchors(html, url);
        Logger.info("extracted links: " + links.size() + " from " + url);

        for (String link : links) {
            if (pageCounter.get() >= MAX_PAGES) break;

            String norm = normalizeUrl(link);
            if (shouldSkipWikipedia(norm)) continue;

            if (Utils.isNonHtmlResource(norm) && !norm.endsWith("/")) continue;

            if (!isUrlInSameDomain(norm)) continue;

            recordLink(source, norm);
            String anchorText = anchorMap.get(norm);
            if (anchorText != null && !anchorText.isBlank()) {
                addAnchorText(norm, anchorText);
            }
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
        double pri = computePriority(url, depth);
        frontier.offer(new FetchTask(url, depth, pri, frontierSeq.incrementAndGet()));
        startFrontierWorker();
    }

    private void processUrl(String url, int depth) {
        addHost(url);
        Logger.info("Crawling: " + url);

        String html = downloader.downloadHybrid(url);
        if (html == null || html.isEmpty()) return;

        String canonical = extractCanonicalUrl(html, url);
        if (shouldSkipByHash(url, canonical, html)) return;
        if (canonical != null && !canonical.isBlank()) {
            visitedLinks.add(canonical);
        }
        savePage(url, canonical, html);

        String source = (canonical != null && !canonical.isBlank()) ? canonical : url;
        List<String> links = extractor.extractLinks(html, url);
        Map<String, String> anchorMap = extractAnchors(html, url);
        Logger.info("extracted links: " + links.size() + " from " + url);

        for (String link : links) {
            if (pageCounter.get() >= MAX_PAGES) break;

            String norm = normalizeUrl(link);
            if (shouldSkipWikipedia(norm)) continue;

            if (Utils.isNonHtmlResource(norm) && !norm.endsWith("/")) continue;

            if (!isUrlInSameDomain(norm)) continue;

            recordLink(source, norm);
            String anchorText = anchorMap.get(norm);
            if (anchorText != null && !anchorText.isBlank()) {
                addAnchorText(norm, anchorText);
            }
            if (depth - 1 > 0) {
                submitUrl(norm, depth - 1);
            }
        }
    }

    private String hostKey(String url) {
        try {
            URI u = new URI(url);
            String host = u.getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (Exception e) {
            return null;
        }
    }

    private double computePriority(String url, int depth) {
        double depthScore = 1.0 / (1 + depth);
        int pathSegments = 0;
        try {
            URI u = new URI(url);
            String path = u.getPath();
            if (path != null && !path.isEmpty()) {
                pathSegments = (int) java.util.Arrays.stream(path.split("/"))
                        .filter(s -> !s.isBlank())
                        .count();
            }
        } catch (Exception ignore) {
        }
        double pathScore = 1.0 / (1 + pathSegments);
        return depthScore * 0.7 + pathScore * 0.3;
    }

    private boolean shouldSkipWikipedia(String norm) {
        if (norm == null) return false;
        String lower = norm.toLowerCase(Locale.ROOT);
        if (lower.contains("/w?")) return true;
        if (lower.contains("action=edit")) return true;
        int hash = lower.indexOf('#');
        if (hash > 0) {
            norm = lower.substring(0, hash);
        } else {
            norm = lower;
        }
        if (!norm.contains("/wiki/")) return false;
        try {
            String path = new URI(norm).getPath();
            if (path != null && path.contains(":")) return true;
        } catch (Exception ignore) {
        }
        if (norm.contains("/wiki/special:")) return true;
        return false;
    }

    private void startFrontierWorker() {
        if (!frontierWorkerRunning.compareAndSet(false, true)) {
            return;
        }
        taskScheduled();
        try {
            executor.submit(this::runFrontier);
        } catch (RejectedExecutionException rex) {
            frontierWorkerRunning.set(false);
            taskDone();
            Logger.warn("frontier worker rejected: " + rex.getMessage());
        }
    }

    private void runFrontier() {
        try {
            while (true) {
                FetchTask task;
                try {
                    task = frontier.poll(2, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (task == null) {
                    if (frontier.isEmpty()) {
                        break;
                    } else {
                        continue;
                    }
                }
                String host = hostKey(task.url);
                if (host == null) {
                    continue;
                }
                HostQueue hq = hostQueues.computeIfAbsent(host, h -> new HostQueue());
                hq.queue.offer(task);
                startHostWorker(host, hq);
            }
        } finally {
            frontierWorkerRunning.set(false);
            taskDone();
            if (!frontier.isEmpty()) {
                startFrontierWorker();
            }
        }
    }

    private void startHostWorker(String host, HostQueue hq) {
        synchronized (hq) {
            if (hq.workerRunning) return;
            hq.workerRunning = true;
        }

        taskScheduled();
        try {
            executor.submit(() -> runHostQueue(host, hq));
        } catch (RejectedExecutionException rex) {
            synchronized (hq) {
                hq.workerRunning = false;
            }
            taskDone();
            Logger.warn("host worker rejected for host " + host + ": " + rex.getMessage());
        }
    }

    private void runHostQueue(String host, HostQueue hq) {
        try {
            while (true) {
                FetchTask task;
                try {
                    task = hq.queue.poll(2, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    synchronized (hq) {
                        hq.workerRunning = false;
                    }
                    break;
                }
                if (task == null) {
                    synchronized (hq) {
                        if (hq.queue.isEmpty()) {
                            hq.workerRunning = false;
                            break;
                        } else {
                            continue;
                        }
                    }
                }

                politeDelay(task.url);
                try {
                    processUrl(task.url, task.depth);
                } catch (Exception e) {
                    Logger.error("error processing " + task.url + ": " + e.getMessage(), e);
                }
            }
        } finally {
            taskDone();
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
            Logger.debug("failed to parse host from: " + url + " (" + e.getMessage() + ")");
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

    private void recordLink(String fromUrl, String toUrl) {
        if (fromUrl == null || toUrl == null) return;
        Set<String> targets = linkGraph.computeIfAbsent(fromUrl, k -> ConcurrentHashMap.newKeySet());
        targets.add(toUrl);
    }

    private void addAnchorText(String targetUrl, String text) {
        if (targetUrl == null || text == null || text.isBlank()) return;
        anchorTexts.computeIfAbsent(targetUrl,
                k -> Collections.synchronizedList(new java.util.ArrayList<>()))
                .add(text.trim());
    }

    private void savePage(String url, String canonicalUrl, String content) {
        savePage(url, canonicalUrl, content, System.currentTimeMillis());
    }

    private void savePage(String url, String canonicalUrl, String content, long fetchedAt) {
        try {
            Path dir = this.pagesDir;

            String nameSource = (canonicalUrl != null && !canonicalUrl.isBlank()) ? canonicalUrl : url;
            String baseName = nameSource.replaceFirst("https?://(www\\.)?", "");
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

            String fileNameToStore = out.getFileName().toString();
            Utils.writeAtomic(out, w -> {
                try {
                    w.write(content);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
            pageMetadata.put(fileNameToStore, new PageMeta(fileNameToStore, url, canonicalUrl, fetchedAt));
            urlToFile.put(url, fileNameToStore);
            if (canonicalUrl != null && !canonicalUrl.isBlank()) {
                urlToFile.put(canonicalUrl, fileNameToStore);
            }

        } catch (Exception e) {
            Logger.error("error saving page: " + e.getMessage(), e);
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
            Logger.error("error saving hosts: " + e.getMessage(), e);
        }
    }

    private boolean shouldSkipByHash(String url, String canonical, String html) {
        try {
            String hash = Utils.sha256(html);
            String prev;
            synchronized (urlHashes) {
                prev = urlHashes.get(url);
                if (prev == null) {
                    urlHashes.put(url, hash);
                }
                if (canonical != null && !canonical.isBlank()) {
                    String prevCanonical = urlHashes.get(canonical);
                    if (prevCanonical == null) {
                        urlHashes.put(canonical, hash);
                    }
                }
            }
            if (prev != null && prev.equals(hash)) {
                Logger.debug("unchanged content; skipping re-fetch for " + url);
                return true;
            }
            boolean fresh = contentHashes.add(hash);
            if (!fresh) {
                Logger.debug("duplicate content detected      skipping page storage.");
                return true;
            }
            return false;
        } catch (Exception e) {
            Logger.debug("failed to hash content for dedup: " + e.getMessage());
            return false;
        }
    }

    private String extractCanonicalUrl(String html, String pageUrl) {
        if (html == null || html.isEmpty()) return pageUrl;
        try {
            Matcher m = CANONICAL_PATTERN.matcher(html);
            if (m.find()) {
                String href = m.group(1);
                URI base = new URI(pageUrl);
                URI resolved = base.resolve(href);
                String norm = normalizeUrl(resolved.toString());
                return norm;
            }
        } catch (Exception e) {
            Logger.debug("Failed to parse canonical for " + pageUrl + ": " + e.getMessage());
        }
        return pageUrl;
    }

    private void politeDelay(String url) {
        try {
            URI u = new URI(url);
            String host = u.getHost();
            if (host == null) return;
            host = host.toLowerCase(Locale.ROOT);
            Object lock = hostLocks.computeIfAbsent(host, h -> new Object());
            synchronized (lock) {
                long now = System.currentTimeMillis();
                long last = hostLastFetch.getOrDefault(host, 0L);
                long wait = MIN_DELAY_MS - (now - last);
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                hostLastFetch.put(host, System.currentTimeMillis());
            }
        } catch (Exception ignore) {
        }
    }

    public Map<String, PageMeta> getPageMetadata() {
        return Collections.unmodifiableMap(pageMetadata);
    }

    public Map<String, Integer> getInlinkCounts() {
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        Map<String, Set<String>> snapshot = new java.util.HashMap<>();
        linkGraph.forEach((k, v) -> snapshot.put(k, v == null ? Collections.emptySet() : new HashSet<>(v)));
        for (Map.Entry<String, Set<String>> e : snapshot.entrySet()) {
            Set<String> targets = e.getValue();
            if (targets == null) continue;
            for (String t : targets) {
                String file = urlToFile.get(t);
                if (file == null) continue;
                counts.merge(file, 1, Integer::sum);
            }
        }
        return counts;
    }

    public Map<String, java.util.List<String>> getAnchorTextByFile() {
        Map<String, java.util.List<String>> byFile = new ConcurrentHashMap<>();
        for (Map.Entry<String, java.util.List<String>> e : anchorTexts.entrySet()) {
            String file = urlToFile.get(e.getKey());
            if (file == null) continue;
            java.util.List<String> list = e.getValue();
            if (list == null) continue;
            java.util.List<String> snapshot;
            synchronized (list) {
                snapshot = new java.util.ArrayList<>(list);
            }
            if (!snapshot.isEmpty()) {
                byFile.computeIfAbsent(file, k -> new java.util.ArrayList<>()).addAll(snapshot);
            }
        }
        return byFile;
    }

    public Map<String, Double> computePageRank() {
        Map<String, Set<String>> snapshot = new java.util.HashMap<>();
        linkGraph.forEach((k, v) -> snapshot.put(k, v == null ? Collections.emptySet() : new HashSet<>(v)));

        Map<String, Set<String>> adjacency = new java.util.HashMap<>();
        for (Map.Entry<String, Set<String>> e : snapshot.entrySet()) {
            String fromUrl = e.getKey();
            String fromFile = urlToFile.get(fromUrl);
            if (fromFile == null) continue;
            Set<String> targets = e.getValue();
            if (targets == null || targets.isEmpty()) continue;
            Set<String> mapped = new java.util.HashSet<>();
            for (String t : targets) {
                String tf = urlToFile.get(t);
                if (tf != null && !tf.equals(fromFile)) {
                    mapped.add(tf);
                }
            }
            adjacency.put(fromFile, mapped);
        }

        Set<String> nodes = new java.util.HashSet<>(pageMetadata.keySet());
        nodes.addAll(adjacency.keySet());

        int n = nodes.size();
        Map<String, Double> pr = new java.util.HashMap<>();
        if (n == 0) return pr;
        double init = 1.0 / n;
        for (String node : nodes) pr.put(node, init);

        double d = 0.85;
        int iterations = 20;
        for (int it = 0; it < iterations; it++) {
            double sinkSum = 0.0;
            for (String node : nodes) {
                Set<String> outs = adjacency.get(node);
                if (outs == null || outs.isEmpty()) {
                    sinkSum += pr.getOrDefault(node, 0.0);
                }
            }

            Map<String, Double> next = new java.util.HashMap<>();
            for (String node : nodes) {
                double val = (1.0 - d) / n;
                val += d * sinkSum / n;

                for (Map.Entry<String, Set<String>> e : adjacency.entrySet()) {
                    String from = e.getKey();
                    Set<String> outs = e.getValue();
                    if (outs == null || outs.isEmpty()) continue;
                    if (outs.contains(node)) {
                        val += d * pr.getOrDefault(from, 0.0) / outs.size();
                    }
                }
                next.put(node, val);
            }
            pr = next;
        }
        return pr;
    }

    public void saveManifest(Path path, Map<String, Double> pageRank) {
        Map<String, Integer> inlinks = getInlinkCounts();
        try {
            Utils.ensureParentDirs(path);
            Utils.writeAtomic(path, w -> {
                try {
                    w.write("[\n");
                    int i = 0;
                    for (PageMeta pm : pageMetadata.values()) {
                        if (i > 0) w.write(",\n");
                        int il = inlinks.getOrDefault(pm.fileName, 0);
                        double pr = pageRank.getOrDefault(pm.fileName, 0.0);
                        w.write("  {\"file\":\"" + jsonEscape(pm.fileName) + "\",");
                        w.write("\"url\":\"" + jsonEscape(pm.url) + "\",");
                        w.write("\"canonical\":\"" + jsonEscape(pm.canonicalUrl) + "\",");
                        w.write("\"fetchedAt\":" + pm.fetchedAt + ",");
                        w.write("\"inlinks\":" + il + ",");
                        w.write("\"pagerank\":" + String.format(java.util.Locale.ROOT, "%.6f", pr) + "}");
                        i++;
                    }
                    w.write("\n]");
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
        } catch (Exception e) {
            Logger.error("Error saving manifest: " + e.getMessage(), e);
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    public static final class PageMeta {
        public final String fileName;
        public final String url;
        public final String canonicalUrl;
        public final long fetchedAt;

        public PageMeta(String fileName, String url, String canonicalUrl, long fetchedAt) {
            this.fileName = fileName;
            this.url = url;
            this.canonicalUrl = canonicalUrl;
            this.fetchedAt = fetchedAt;
        }
    }
}
