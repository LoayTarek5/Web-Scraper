package org.example;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;


public class Main {
    public static void main(String[] args) {
        ScraperStats stats = ScraperStats.builder().build();
        stats.startSession();
        // Example book website URL
        String bookWebsiteUrl = "http://books.toscrape.com/";
        System.out.println("Starting book details extraction from: " + bookWebsiteUrl);
        try {
            // Create a WebScraper instance
            WebScraper scraper = WebScraper.builder()
                    .timeout(15000)  // 15 seconds timeout
                    .delayBetweenRequests(1000)  // 1 second between requests
                    .build();
            // Create a ScrapeData object to store the results
            ScrapeData data = new ScrapeData();
            data.setUrl(bookWebsiteUrl);

            // Fetch the page document
            Document doc = Jsoup.connect(bookWebsiteUrl)
                    .userAgent("Mozilla/5.0")
                    .timeout(15000)
                    .get();

            // Now we can directly call the extractBookDetails method
            System.out.println("Extracting book details...");
            scraper.extractBookDetails(bookWebsiteUrl, data, doc);
            stats.trackSuccessfulScrape(data);
            // Display results
            System.out.println("\n=== Book Extraction Results ===");

            // Get book count
            String bookCount = data.getMetadata("book_count");
            System.out.println("Total books found: " + (bookCount != null ? bookCount : "Unknown"));
        /*
            // Display book details from metadata
            Map<String, String> metadata = data.metadata;
            if (metadata != null) {
                System.out.println("\n--- Book Details ---");
                for (Map.Entry<String, String> entry : metadata.entrySet()) {
                    String key = entry.getKey();
                    if (key.startsWith("book_") && key.contains("_price")) {
                        // Extract the book name from the key
                        String bookId = key.substring(5, key.lastIndexOf("_price"));
                        String bookName = bookId.replace("_", " ");

                        // Get details for this book
                        String price = metadata.get("book_" + bookId + "_price");
                        String discount = metadata.get("book_" + bookId + "_discount");
                        String buyers = metadata.get("book_" + bookId + "_buyers");

                        System.out.println("\nBook: " + bookName);
                        System.out.println("Price: " + (price != null ? price : "Not available"));
                        System.out.println("Discount: " + (discount != null ? discount : "None"));
                        System.out.println("Buyers: " + (buyers != null ? buyers : "Unknown"));
                    }
                }
            }
            */
            // Display the content which contains the book details in text format
            System.out.println("\n=== Content with Book Details ===");
            System.out.println(data.getContent());
            stats.endSession();

        } catch (Exception e) {
            System.err.println("Error during book extraction: " + e.getMessage());
            e.printStackTrace();
        }


    }
}

