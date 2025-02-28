package org.example;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Comparator;


@Slf4j
public class ScraperStats {
    // Counters for tracking various metrics
    @Getter
    private final AtomicInteger totalUrlsProcessed = new AtomicInteger(0);
    @Getter
    private final AtomicInteger successfulScrapes = new AtomicInteger(0);
    @Getter
    private final AtomicInteger failedScrapes = new AtomicInteger(0);
    @Getter
    private final AtomicInteger urlsQueued = new AtomicInteger(0);
    @Getter
    private final AtomicLong totalBytesDownloaded = new AtomicLong(0);
    @Getter
    private final AtomicLong totalProcessingTime = new AtomicLong(0);

    // Track per-domain statistics
    private final Map<String, DomainStats> domainStatsMap = new ConcurrentHashMap<>();

    // Track timing information
    private Instant startTime;
    private Instant endTime;

    // Track content type statistics
    private final Map<String, AtomicInteger> contentTypeStats = new ConcurrentHashMap<>();

    // Track error type statistics
    private final Map<String, AtomicInteger> errorStats = new ConcurrentHashMap<>();

    public ScraperStats() {
        reset();
    }


    public void reset() {
        totalUrlsProcessed.set(0);
        successfulScrapes.set(0);
        failedScrapes.set(0);
        urlsQueued.set(0);
        totalBytesDownloaded.set(0);
        totalProcessingTime.set(0);
        domainStatsMap.clear();
        contentTypeStats.clear();
        errorStats.clear();
        startTime = Instant.now();
        endTime = null;
    }


    public void startSession() {
        reset();
        log.info("Scraper statistics tracking started at {}",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }


    public void endSession() {
        endTime = Instant.now();
        log.info("Scraper statistics tracking ended at {}",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        log.info(getSummary());
    }


    public void trackUrlQueued(String url) {
        urlsQueued.incrementAndGet();
    }

    public void trackSuccessfulScrape(ScrapeData data) {
        successfulScrapes.incrementAndGet();
        totalUrlsProcessed.incrementAndGet();

        // Track content length
        if (data.getContent() != null) {
            totalBytesDownloaded.addAndGet(data.getContent().length());
        }

        // Track processing time
        totalProcessingTime.addAndGet(data.getScrapeDurationMs());

        // Track content type
        if (data.getContentType() != null) {
            contentTypeStats.computeIfAbsent(data.getContentType(), k -> new AtomicInteger(0))
                    .incrementAndGet();
        }

        // Track domain stats
        updateDomainStats(data, true);
    }


    public void trackFailedScrape(ScrapeData data) {
        failedScrapes.incrementAndGet();
        totalUrlsProcessed.incrementAndGet();

        // Track error type
        String errorType = categorizeError(data.getErrorMessage());
        errorStats.computeIfAbsent(errorType, k -> new AtomicInteger(0))
                .incrementAndGet();

        // Track domain stats
        updateDomainStats(data, false);
    }


    private void updateDomainStats(ScrapeData data, boolean successful) {
        try {
            String domain = new java.net.URL(data.getUrl()).getHost();
            DomainStats stats = domainStatsMap.computeIfAbsent(domain, k -> new DomainStats());

            if (successful) {
                stats.successCount.incrementAndGet();
                stats.totalBytes.addAndGet(data.getContent() != null ? data.getContent().length() : 0);
                stats.totalTime.addAndGet(data.getScrapeDurationMs());
            } else {
                stats.failureCount.incrementAndGet();
            }
        } catch (Exception e) {
            log.warn("Could not extract domain from URL: {}", data.getUrl());
        }
    }


    private String categorizeError(String errorMessage) {
        if (errorMessage == null) {
            return "Unknown";
        }

        String lowerCaseError = errorMessage.toLowerCase();

        if (lowerCaseError.contains("timeout")) {
            return "Timeout";
        } else if (lowerCaseError.contains("404")) {
            return "Not Found (404)";
        } else if (lowerCaseError.contains("403")) {
            return "Forbidden (403)";
        } else if (lowerCaseError.contains("connection")) {
            return "Connection Error";
        } else if (lowerCaseError.contains("ssl") || lowerCaseError.contains("certificate")) {
            return "SSL/Certificate Error";
        } else {
            return "Other";
        }
    }


    public Duration getTotalDuration() {
        Instant end = endTime != null ? endTime : Instant.now();
        return Duration.between(startTime, end);
    }


    public double getAverageProcessingTime() {
        int successful = successfulScrapes.get();
        return successful > 0 ? (double) totalProcessingTime.get() / successful : 0;
    }


    public double getSuccessRate() {
        int total = totalUrlsProcessed.get();
        return total > 0 ? (double) successfulScrapes.get() * 100 / total : 0;
    }


    public String getMostScrapedDomain() {
        return domainStatsMap.entrySet().stream()
                .max(Map.Entry.comparingByValue((d1, d2) ->
                        Integer.compare(d1.successCount.get(), d2.successCount.get())))
                .map(Map.Entry::getKey)
                .orElse("None");
    }

    public String getHighestFailureDomain() {
        return domainStatsMap.entrySet().stream()
                .filter(e -> e.getValue().getTotalCount() >= 5) // Minimum sample size
                .max(Map.Entry.comparingByValue((d1, d2) ->
                        Double.compare(d1.getFailureRate(), d2.getFailureRate())))
                .map(Map.Entry::getKey)
                .orElse("None");
    }

    public String getMostCommonError() {
        return errorStats.entrySet().stream()
                .max(Comparator.comparingInt(entry -> entry.getValue().get()))
                .map(entry -> String.format("%s (%d occurrences)", entry.getKey(), entry.getValue().get()))
                .orElse("None");
    }

    public String getMostCommonContentType() {
        if (contentTypeStats.isEmpty()) {
            return "None";
        }

        return contentTypeStats.entrySet().stream()
                .max(Comparator.comparingInt(entry -> entry.getValue().get()))
                .map(entry -> String.format("%s (%d occurrences)", entry.getKey(), entry.getValue().get()))
                .orElse("None");
    }

    public String getSummary() {
        Duration duration = getTotalDuration();
        long seconds = duration.getSeconds();
        double ratePerSecond = seconds > 0 ?
                (double) totalUrlsProcessed.get() / seconds : 0;

        return String.format("""
                        Scraping Statistics Summary:
                        -----------------------------
                        Total Duration: %d minutes %d seconds
                        URLs Processed: %d (%.2f URLs/second)
                        Successful: %d (%.2f%%)
                        Failed: %d (%.2f%%)
                        URLs Remaining in Queue: %d
                        
                        Data Downloaded: %s
                        Average Processing Time: %.2f ms per URL
                        
                        Domain Statistics:
                        - Most Scraped Domain: %s
                        - Highest Failure Rate: %s
                        
                        Error Analysis:
                        - Most Common Error: %s
                        
                        Content Analysis:
                        - Most Common Content Type: %s
                        """,
                duration.toMinutes(), duration.minusMinutes(duration.toMinutes()).getSeconds(),
                totalUrlsProcessed.get(), ratePerSecond,
                successfulScrapes.get(), getSuccessRate(),
                failedScrapes.get(), 100 - getSuccessRate(),
                urlsQueued.get(),
                formatBytes(totalBytesDownloaded.get()),
                getAverageProcessingTime(),
                getMostScrapedDomain(),
                getHighestFailureDomain(),
                getMostCommonError(),
                getMostCommonContentType());
    }

    public String getProgressSummary() {
        Duration duration = getTotalDuration();
        long seconds = duration.getSeconds();
        double ratePerSecond = seconds > 0 ?
                (double) totalUrlsProcessed.get() / seconds : 0;

        return String.format(
                "Progress: %d processed (%d successful, %d failed) at %.2f URLs/sec, %d in queue",
                totalUrlsProcessed.get(), successfulScrapes.get(),
                failedScrapes.get(), ratePerSecond, urlsQueued.get());
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    private static class DomainStats implements Comparable<DomainStats> {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicLong totalBytes = new AtomicLong(0);
        AtomicLong totalTime = new AtomicLong(0);

        public int getTotalCount() {
            return successCount.get() + failureCount.get();
        }

        public double getFailureRate() {
            int total = getTotalCount();
            return total > 0 ? (double) failureCount.get() / total : 0;
        }

        public double getAverageBytes() {
            int success = successCount.get();
            return success > 0 ? (double) totalBytes.get() / success : 0;
        }

        public double getAverageTime() {
            int success = successCount.get();
            return success > 0 ? (double) totalTime.get() / success : 0;
        }

        @Override
        public int compareTo(DomainStats other) {
            return Integer.compare(this.getTotalCount(), other.getTotalCount());
        }
    }

    public static class Builder {
        private final ScraperStats stats;

        public Builder() {
            stats = new ScraperStats();
        }

        public ScraperStats build() {
            return stats;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}