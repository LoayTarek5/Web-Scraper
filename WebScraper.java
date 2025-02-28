package org.example;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;


@Slf4j // static logger instance(popular logging API)
public class WebScraper {
    // Some Configurations
    private int threadCount;
    private int timeout;
    private long delay;
    private RateLimiter rateLimiter;

    // Proceeded data
    private Queue<String> urlQueue;
    private Set<String> visitedUrls;
    private ExecutorService executorService;
    private CompletionService<ScrapeData> completionService;
    private List<ScrapeData> results;

    // State tracking
    private AtomicBoolean isRunning;
    private Map<String, LocalDateTime> lastAccessTimes;
    private int successCount;
    private int errorCount;

    // Constructor
    public WebScraper(int threadCount, int timeout, long delay) {
        this.threadCount = threadCount;
        this.timeout = timeout;
        this.delay = delay;
        this.rateLimiter = RateLimiter.builder().defaultLimit(5, Duration.ofSeconds(5)).build();


        this.urlQueue = new ConcurrentLinkedQueue<>();
        this.visitedUrls = Collections.synchronizedSet(new HashSet<>());
        this.executorService = Executors.newFixedThreadPool(threadCount);
        this.completionService = new ExecutorCompletionService<>(executorService);
        this.results = Collections.synchronizedList(new ArrayList<>());

        this.isRunning = new AtomicBoolean(false);
        this.lastAccessTimes = new ConcurrentHashMap<>();
    }

    // Methods
    public void addUrl(String url) {
        if (url != null && !url.isEmpty() && !visitedUrls.contains(url)) {
            urlQueue.offer(url);
            log.info("Added URL to queue: {}", url);
        }
    }

    public void addUrls(Collection<String> urls) {
        for (String url : urls) {
            addUrl(url);
        }
    }

    // Start Web Scraping
    public void start() {
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("WebScraper is already running");
            return;
        }

        log.info("Start Web Scraper with {} threads", threadCount);
        int processTask = 0;
        try {
            while (!urlQueue.isEmpty() || processTask > 0) {
                log.info("Queue size: {}, Active tasks: {}", urlQueue.size(), processTask);

                // Submit New Task
                while (!urlQueue.isEmpty() && processTask < threadCount) {
                    String url = urlQueue.poll();
                    if (url != null && !visitedUrls.contains(url)) {
                        log.info("Submitting task for URL: {}", url);
                        completionService.submit(() -> scrapePage(url));
                        processTask++;
                        visitedUrls.add(url);
                    }
                }

                // Process Current URL(Task)
                try {
                    log.info("Waiting for task completion...");
                    Future<ScrapeData> completed = completionService.poll(5000, TimeUnit.MILLISECONDS);
                    if (completed != null) {
                        try {
                            log.info("Task completed");
                            ScrapeData result = completed.get();
                            CheckResult(result);
                            processTask--;
                        } catch (ExecutionException e) {
                            log.error("Error Executing Scraping This Task", e);
                            errorCount++;
                            processTask--;
                        }
                    } else {
                        log.warn("No task completed in the last 5 seconds, still waiting...");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            shutdown();
        }
    }

    // Get All Data Needed From URLs(Tasks)
    private ScrapeData scrapePage(String url) {
        try {

            rateLimiter.acquire(url);

            log.info("Scraping URL: {}", url);
            long startTime = System.currentTimeMillis();

            // Fetch and parse the page
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .timeout(timeout)
                    .get();
            // Create scraped data
            ScrapeData data = ScrapeData.builder()
                    .url(url)
                    .title(doc.title())
                    .content(doc.body().text())
                    .scrapeDate(LocalDateTime.now())
                    .successful(true)
                    .scrapeDurationMs(System.currentTimeMillis() - startTime)
                    .build();

            // Extract metadata
            data.setMetadata("charset", doc.charset().name());
            data.setMetadata("documentSize", String.valueOf(doc.html().length()));

            return data;

        } catch (IOException e) {
            log.error("Error scraping {}: {}", url, e.getMessage());
            return ScrapeData.createFailed(url, e.getMessage());
        }
    }

    // Delay Task(s) If Needed
    public void waitTime(String url) {
        String domain = extractDomain(url);
        LocalDateTime lastAccess = lastAccessTimes.get(domain);

        if (lastAccess != null) {
            Instant lastAccessInstant = lastAccess.toInstant(ZoneOffset.UTC);
            long lastAccessMillis = lastAccessInstant.toEpochMilli();
            long currentTimeMillis = System.currentTimeMillis();
            long diffLastAccess = currentTimeMillis - lastAccessMillis;
            //long diffLastAccess = System.currentTimeMillis() - lastAccess.toInstant(java.time.ZoneOffset.UTC).toEpochMilli();

            if (diffLastAccess < delay) {
                try {
                    Thread.sleep(delay - diffLastAccess);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        lastAccessTimes.put(domain, LocalDateTime.now());
    }

    // Extract Domain From URL
    public String extractDomain(String url) {
        try {
            return new URL(url).getHost();
        } catch (MalformedURLException e) {
            return url;
        }
    }

    // Track Of Successful URLs(Tasks)
    private void CheckResult(ScrapeData result) {
        if (result.isSuccessful()) {
            successCount++;
            results.add(result);
            log.info("Successfully scraped: {}", result.getUrl());
        } else {
            errorCount++;
            log.error("Failed to scrape {}: {}", result.getUrl(), result.getErrorMessage());
        }
    }

    // End of Scraping All URLs And Print Info
    public void shutdown() {
        isRunning.set(false);
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Web scraper shutdown complete. Successful: {}, Failed: {}", successCount, errorCount);
    }

    // Extracted book details method integrated into WebScraper
    public void extractBookDetails(String url, ScrapeData data, Document doc) {
        try {
            // If we already have the Document, use it instead of fetching again
            if (doc == null) {
                doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0")
                        .timeout(timeout)
                        .get();
            }

            // Find all book items on the page
            Elements bookItems = doc.select("article.product_pod");

            log.info("Found {} books on page {}", bookItems.size(), url);

            StringBuilder booksContent = new StringBuilder();
            booksContent.append("Book Details:\n");

            for (Element bookItem : bookItems) {
                // Extract book name
                String name = bookItem.select("h3 a").attr("title");

                // Extract price
                String price = bookItem.select(".price_color").text();

                // Extract discount if available
                String availability = bookItem.select(".availability").text();
                String discount = bookItem.select(".instock.availability").isEmpty() ? "No discount" : "In stock";

                // Extract rating
                String rating = bookItem.select("p.star-rating").attr("class").replace("star-rating ", "");
                String buyers = convertRatingToBuyers(rating);

                // Add data to the scrape data
                String bookKey = "book_" + name.replaceAll("\\s+", "_").toLowerCase();
                data.setMetadata(bookKey + "_price", price);
                data.setMetadata(bookKey + "_discount", discount);
                data.setMetadata(bookKey + "_buyers", buyers);

                // Append to content for summary
                booksContent.append("\nBook: ").append(name)
                        .append("\nPrice: ").append(price)
                        .append("\nAvailability: ").append(discount)
                        .append("\nBuyers: ").append(buyers)
                        .append("\n");
            }

            // Add book count metadata
            data.setMetadata("book_count", String.valueOf(bookItems.size()));

            // Append book details to the content
            String originalContent = data.getContent();
            data.setContent(originalContent + "\n\n" + booksContent.toString());

        } catch (Exception e) {
            log.error("Error extracting book details for {}: {}", url, e.getMessage());
            data.setMetadata("book_extraction_error", e.getMessage());
        }
    }

    // Helper method to convert star ratings to estimated buyers
    private String convertRatingToBuyers(String rating) {
        return switch (rating) {
            case "One" -> "Approximately 10-50 buyers";
            case "Two" -> "Approximately 50-100 buyers";
            case "Three" -> "Approximately 100-200 buyers";
            case "Four" -> "Approximately 200-500 buyers";
            case "Five" -> "Approximately 500+ buyers";
            default -> "Unknown number of buyers";
        };
    }

    // This can be called independently of the scraper queue
    public ScrapeData extractBooksFromUrl(String url) {
        log.info("Directly extracting book details from URL: {}", url);

        try {
            ScrapeData data = scrapePage(url);
            if (data.isSuccessful()) {
                log.info("Book extraction completed successfully for URL: {}", url);
            } else {
                log.error("Book extraction failed for URL: {}", url);
            }
            return data;
        } catch (Exception e) {
            log.error("Exception during book extraction for URL: {}", url, e);
            return ScrapeData.createFailed(url, "Book extraction failed: " + e.getMessage());
        }
    }


    public List<ScrapeData> getResults() {
        return new ArrayList<>(results);
    }

    public String getStatistics() {
        return String.format("""
                        Scraping Statistics:
                        Total URLs processed: %d
                        Successful scrapes: %d
                        Failed scrapes: %d
                        URLs in queue: %d
                        """,
                visitedUrls.size(),
                successCount,
                errorCount,
                urlQueue.size());
    }

    // Builder pattern for WebScraper
    public static class Builder {
        private int threadCount = Runtime.getRuntime().availableProcessors();
        private int timeout = 10000;
        private long delayBetweenRequests = 1000;

        public Builder threadCount(int threadCount) {
            this.threadCount = threadCount;
            return this;
        }

        public Builder timeout(int timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder delayBetweenRequests(long delay) {
            this.delayBetweenRequests = delay;
            return this;
        }

        public WebScraper build() {
            return new WebScraper(threadCount, timeout, delayBetweenRequests);
        }
    }

    // Static builder method
    public static Builder builder() {
        return new Builder();
    }

}
