package org.example;

import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@Slf4j
public class DataProcessor {
    private final List<ScrapeData> processedData;
    private final Map<String, Pattern> contentFilters;
    private final Map<String, Integer> domainStats;
    private final List<ProcessingListener> listeners;

    // Configuration
    private int maxContentLength;
    private boolean removeEmptyContent;
    private boolean sanitizeContent;

    public DataProcessor() {
        this.processedData = new ArrayList<>();
        this.contentFilters = new ConcurrentHashMap<>();
        this.domainStats = new ConcurrentHashMap<>();
        this.listeners = new ArrayList<>();

        // Default configurations
        this.maxContentLength = 100000; // 100KB
        this.removeEmptyContent = true;
        this.sanitizeContent = true;
    }

    public void processData(ScrapeData data) {
        if (data == null) {
            log.warn("Received null ScrapeData object");
            return;
        }

        try {
            // Pre-processing
            if (preProcess(data)) {
                // Content processing
                processContent(data);

                // Post-processing
                postProcess(data);

                // Store processed data
                synchronized (processedData) {
                    processedData.add(data);
                }

                // Update statistics
                updateStats(data);

                // Notify listeners
                notifyListeners(data);

                log.info("Successfully processed data from URL: {}", data.getUrl());
            }
        } catch (Exception e) {
            log.error("Error processing data from URL: {}", data.getUrl(), e);
            data.setSuccessful(false);
            data.setErrorMessage("Processing error: " + e.getMessage());
        }
    }


    private boolean preProcess(ScrapeData data) {
        // Check if content is valid
        if (!data.validContent() && removeEmptyContent) {
            log.debug("Skipping empty content from URL: {}", data.getUrl());
            return false;
        }

        // Trim content if it exceeds max length
        if (data.getContent() != null && data.getContent().length() > maxContentLength) {
            data.setContent(data.getContent().substring(0, maxContentLength));
            log.debug("Content truncated for URL: {}", data.getUrl());
        }

        return true;
    }

    private void processContent(ScrapeData data) {
        String content = data.getContent();

        if (content == null) {
            return;
        }

        // Apply content filters
        for (Map.Entry<String, Pattern> filter : contentFilters.entrySet()) {
            content = filter.getValue().matcher(content).replaceAll("");
        }

        // Sanitize content if enabled
        if (sanitizeContent) {
            content = sanitizeContent(content);
        }

        data.setContent(content);
    }

    private void postProcess(ScrapeData data) {
        // Extract and store additional metadata
        if (data.getContent() != null) {
            data.setMetadata("wordCount", String.valueOf(data.getContent().split("\\s+").length));
            data.setMetadata("processingTime", String.valueOf(System.currentTimeMillis()));
        }

        // Store price and discount in metadata if needed
        if (data.getPrice() != null) {
            data.setMetadata("price", data.getPrice());
        }
        if (data.getDiscount() != null) {
            data.setMetadata("discount", data.getDiscount());
        }
    }

    private String sanitizeContent(String content) {
        // Remove HTML tags if any remain
        content = content.replaceAll("<[^>]*>", "");

        // Remove extra whitespace
        content = content.replaceAll("\\s+", " ").trim();

        // Remove special characters
        content = content.replaceAll("[^\\p{Print}]", "");

        return content;
    }

    private void updateStats(ScrapeData data) {
        try {
            String domain = new java.net.URL(data.getUrl()).getHost();
            domainStats.merge(domain, 1, Integer::sum);
        } catch (Exception e) {
            log.warn("Could not update stats for URL: {}", data.getUrl());
        }
    }

    // Configuration methods
    public void addContentFilter(String name, String regex) {
        contentFilters.put(name, Pattern.compile(regex));
    }

    public void removeContentFilter(String name) {
        contentFilters.remove(name);
    }

    public void setMaxContentLength(int maxLength) {
        this.maxContentLength = maxLength;
    }

    public void setRemoveEmptyContent(boolean remove) {
        this.removeEmptyContent = remove;
    }

    public void setSanitizeContent(boolean sanitize) {
        this.sanitizeContent = sanitize;
    }

    // Listener pattern for processing events
    public interface ProcessingListener {
        void onDataProcessed(ScrapeData data);
    }

    public void addListener(ProcessingListener listener) {
        listeners.add(listener);
    }

    private void notifyListeners(ScrapeData data) {
        for (ProcessingListener listener : listeners) {
            try {
                listener.onDataProcessed(data);
            } catch (Exception e) {
                log.error("Error notifying listener", e);
            }
        }
    }

    // Getters for processed data and statistics
    public List<ScrapeData> getProcessedData() {
        return new ArrayList<>(processedData);
    }

    public Map<String, Integer> getDomainStats() {
        return new ConcurrentHashMap<>(domainStats);
    }

    public List<ScrapeData> getFilteredData(Predicate<ScrapeData> filter) {
        return processedData.stream()
                .filter(filter)
                .toList();
    }

    // Builder pattern
    public static class Builder {
        private final DataProcessor processor;

        public Builder() {
            processor = new DataProcessor();
        }

        public Builder maxContentLength(int length) {
            processor.setMaxContentLength(length);
            return this;
        }

        public Builder removeEmptyContent(boolean remove) {
            processor.setRemoveEmptyContent(remove);
            return this;
        }

        public Builder sanitizeContent(boolean sanitize) {
            processor.setSanitizeContent(sanitize);
            return this;
        }

        public Builder addFilter(String name, String regex) {
            processor.addContentFilter(name, regex);
            return this;
        }

        public DataProcessor build() {
            return processor;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}