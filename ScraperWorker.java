package org.example;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScraperWorker implements Runnable {
    private static final Logger logger = Logger.getLogger(ScraperWorker.class.getName());

    private final String url;
    private final DataProcessor dataProcessor;
    private final int maxRetries;
    private final int timeout;
    private final String userAgent;

    // Store any found URLs for further scraping
    private final List<String> discoveredUrls;

    public ScraperWorker(String url, DataProcessor dataProcessor, int maxRetries, int timeout) {
        this.url = url;
        this.dataProcessor = dataProcessor;
        this.maxRetries = maxRetries;
        this.timeout = timeout;
        this.userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
        this.discoveredUrls = new ArrayList<>();
    }

    @Override
    public void run() {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount < maxRetries) {
            try {
                ScrapeData data = scrapePage();
                if (data.isSuccessful()) {
                    // Process and save the scraped data
                    dataProcessor.processData(data);
                    return;
                }
            } catch (Exception e) {
                lastException = e;
                retryCount++;
                logger.warning(String.format("Retry %d failed for URL %s: %s",
                        retryCount, url, e.getMessage()));

                // Wait before retrying
                try {
                    Thread.sleep(retryCount * 1000L); // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        // If we get here, all retries failed
        handleFailure(lastException);
    }

    private ScrapeData scrapePage() throws IOException {
        logger.info("Starting to scrape URL: " + url);
        long startTime = System.currentTimeMillis();

        Document doc = fetchPage();
        if (doc == null) {
            return ScrapeData.createFailed(url, "Failed to fetch page");
        }

        // Extract basic information
        String title = doc.title();
        String content = doc.body().text();
        String price = extractPrice(doc);
        String discount = extractDiscount(doc);

        // Extract metadata
        Elements metaTags = doc.select("meta");
        Elements links = doc.select("a[href]");

        // Create ScrapedData object
        ScrapeData scrapedData = new ScrapeData();
        scrapedData.setUrl(url);
        scrapedData.setTitle(title);
        scrapedData.setContent(content);
        scrapedData.setScrapeDate(LocalDateTime.now());
        scrapedData.setSuccessful(true);
        scrapedData.setScrapeDurationMs(System.currentTimeMillis() - startTime);
        scrapedData.setPrice(price);
        scrapedData.setDiscount(discount);

        // Add metadata
        processMetadata(scrapedData, metaTags);

        // Process discovered links
        processLinks(links);

        return scrapedData;
    }

    private String extractPrice(Document doc) {
        // Try multiple common price selectors
        Element priceElement = doc.selectFirst(".price, .product-price, span[itemprop=price], .current-price");

        if (priceElement == null) {
            // Try to find by text pattern (e.g., $xx.xx)
            Elements elements = doc.select("*:containsOwn($)");
            for (Element element : elements) {
                String text = element.text();
                if (text.matches(".*\\$\\d+\\.\\d{2}.*")) {
                    return text.trim();
                }
            }
            return null;
        }

        return priceElement.text();
    }

    private String extractDiscount(Document doc) {
        // Try multiple common discount selectors
        Element discountElement = doc.selectFirst(".discount, .sale-price, .price-cut, .savings");

        if (discountElement == null) {
            // Try to find by text pattern (e.g., 20% off)
            Elements elements = doc.select("*:containsOwn(%)");
            for (Element element : elements) {
                String text = element.text();
                if (text.matches(".*\\d+%\\s+off.*")) {
                    return text.trim();
                }
            }
            return null;
        }

        return discountElement.text();
    }
    private Document fetchPage() throws IOException {
        return Jsoup.connect(url)
                .userAgent(userAgent)
                .timeout(timeout)
                .followRedirects(true)
                .get();
    }

    private void processMetadata(ScrapeData data, Elements metaTags) {
        for (Element meta : metaTags) {
            String name = meta.attr("name");
            String content = meta.attr("content");
            if (!name.isEmpty() && !content.isEmpty()) {
                data.setMetadata(name, content);
            }
        }
    }

    private void processLinks(Elements links) {
        for (Element link : links) {
            String href = link.attr("abs:href"); // Get absolute URL
            if (isValidUrl(href)) {
                discoveredUrls.add(href);
            }
        }
    }

    private boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        // Basic URL validation
        try {
            new java.net.URL(url);
            return url.startsWith("http://") || url.startsWith("https://");
        } catch (Exception e) {
            return false;
        }
    }

    private void handleFailure(Exception exception) {
        ScrapeData failedData = new ScrapeData();
        failedData.setUrl(url);
        failedData.setScrapeDate(LocalDateTime.now());
        failedData.setSuccessful(false);
        failedData.setErrorMessage(exception != null ?
                exception.getMessage() : "Maximum retries exceeded");

        // Process the failed scrape data
        dataProcessor.processData(failedData);
    }

    // Getter for discovered URLs
    public List<String> getDiscoveredUrls() {
        return new ArrayList<>(discoveredUrls);
    }

    // Builder pattern for ScraperWorker
    public static class Builder {
        private String url;
        private DataProcessor dataProcessor;
        private int maxRetries = 3;
        private int timeout = 10000;

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder dataProcessor(DataProcessor dataProcessor) {
            this.dataProcessor = dataProcessor;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder timeout(int timeout) {
            this.timeout = timeout;
            return this;
        }

        public ScraperWorker build() {
            if (url == null || dataProcessor == null) {
                throw new IllegalStateException("URL and DataProcessor must be set");
            }
            return new ScraperWorker(url, dataProcessor, maxRetries, timeout);
        }
    }

    // Static builder method
    public static Builder builder() {
        return new Builder();
    }
}