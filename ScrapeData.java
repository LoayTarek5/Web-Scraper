package org.example;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScrapeData {
    // The Main Information
    private String url;
    private String title;
    private String content;
    private String price;
    private String discount;
    private LocalDateTime scrapeDate;

    // Http Details
    private int httpStatus;
    private String contentType;
    private long contentLength;

    // Metadata and Header fields
    Map<String, String> metadata;
    Map<String, String> headers;
    Map<String, String> cookies;

    // Error tracking
    private boolean successful;
    private String errorMessage;

    // Scraping metrics
    private long scrapeDurationMs;
    private int retryCount;



    // Constructor
    ScrapeData(String url, String title, String content, LocalDateTime scrapeDate) {
        this.url = url;
        this.title = title;
        this.content = content;
        this.scrapeDate = scrapeDate;
        this.metadata = new HashMap<>();
        this.headers = new HashMap<>();
        this.successful = true;
    }

    // Getters
    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getScrapeDate() {
        return scrapeDate;
    }

    public String getMetadata(String key) {
        if (metadata == null || !metadata.containsKey(key)) {
            return null;
        }
        return metadata.get(key);
    }
    public String getPrice() {
        return price;
    }

    public String getDiscount() {
        return discount;
    }

    // Setters
    public void setUrl(String url) {
        this.url = url;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setScrapeDate(LocalDateTime scrapeDate) {
        this.scrapeDate = scrapeDate;
    }

    public void setPrice(String price) {
        this.price = price;
    }
    public void setDiscount(String discount) {
        this.discount = discount;
    }

    public void setMetadata(String key, String value) {
        // check if the hashmap do not initialize
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        metadata.put(key, value);
    }

    public void addHeader(String key, String value) {
        if (headers == null) {
            headers = new HashMap<>();
        }
        headers.put(key, value);
    }

    public String getHeader(String key) {
        return headers != null ? headers.get(key) : null;
    }

    public boolean validContent() {
        if (successful && content != null && !content.isEmpty()) {
            return true;
        }
        return false;
    }

    public String getContentPreview(int maxLength) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return content.length() <= maxLength ?
                content :
                content.substring(0, maxLength) + "...";
    }

    public String getSummary() {
        return String.format("URL: %s%nTitle: %s%nScrape Date: %s%nStatus: %s%nContent Length: %d",
                url,
                title,
                scrapeDate,
                successful ? "Success" : "Failed",
                content != null ? content.length() : 0);
    }

    public static ScrapeDataBuilder builder() {
        return new ScrapeDataBuilder();
    }

    // Example usage of builder pattern
    public static ScrapeData createSuccessful(String url, String title, String content) {
        return ScrapeData.builder()
                .url(url)
                .title(title)
                .content(content)
                .scrapeDate(LocalDateTime.now())
                .successful(true)
                .build();
    }

    // Create failed scrape instance
    public static ScrapeData createFailed(String url, String errorMessage) {
        return ScrapeData.builder()
                .url(url)
                .scrapeDate(LocalDateTime.now())
                .successful(false)
                .errorMessage(errorMessage)
                .build();
    }
}


