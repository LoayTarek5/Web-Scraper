package org.example;

import lombok.extern.slf4j.Slf4j;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;


@Slf4j
public class RateLimiter {
    // Maps domains to their respective rate limiting configs
    private final Map<String, RateLimit> domainLimits;

    // Maps domains to their last access time
    private final Map<String, Instant> lastAccessTimes;

    // Maps domains to their access counters
    private final Map<String, Integer> accessCounters;

    // Maps regex patterns for domains to their rate limits
    private final Map<Pattern, RateLimit> patternLimits;

    // Default rate limit for domains without specific settings
    private RateLimit defaultLimit;

    // Lock for thread safety
    private final Lock lock;


    public static class RateLimit {
        private final int requestsPerPeriod;
        private final Duration period;
        private final Duration minDelay;

        public RateLimit(int requestsPerPeriod, Duration period, Duration minDelay) {
            this.requestsPerPeriod = requestsPerPeriod;
            this.period = period;
            this.minDelay = minDelay;
        }

        public static RateLimit of(int requests, Duration period) {
            return new RateLimit(requests, period, Duration.ofMillis(0));
        }

        public static RateLimit of(int requests, Duration period, Duration minDelay) {
            return new RateLimit(requests, period, minDelay);
        }
    }


    public RateLimiter() {
        this.domainLimits = new ConcurrentHashMap<>();
        this.lastAccessTimes = new ConcurrentHashMap<>();
        this.accessCounters = new ConcurrentHashMap<>();
        this.patternLimits = new ConcurrentHashMap<>();
        this.lock = new ReentrantLock();

        // Default rate limit: 10 requests per 10 seconds with 500ms minimum delay
        this.defaultLimit = RateLimit.of(
                10,
                Duration.ofSeconds(10),
                Duration.ofMillis(500));
    }


    public void setDefaultRateLimit(int requestsPerPeriod, Duration period, Duration minDelay) {
        this.defaultLimit = RateLimit.of(requestsPerPeriod, period, minDelay);
        log.info("Default rate limit set to {} requests per {}ms with {}ms minimum delay",
                requestsPerPeriod, period.toMillis(), minDelay.toMillis());
    }


    public void addDomainLimit(String domain, int requestsPerPeriod, Duration period) {
        domainLimits.put(domain, RateLimit.of(requestsPerPeriod, period));
        log.info("Rate limit for domain {} set to {} requests per {}ms",
                domain, requestsPerPeriod, period.toMillis());
    }


    public void addDomainLimit(String domain, int requestsPerPeriod, Duration period, Duration minDelay) {
        domainLimits.put(domain, RateLimit.of(requestsPerPeriod, period, minDelay));
        log.info("Rate limit for domain {} set to {} requests per {}ms with {}ms minimum delay",
                domain, requestsPerPeriod, period.toMillis(), minDelay.toMillis());
    }


    public void addPatternLimit(String domainPattern, int requestsPerPeriod, Duration period, Duration minDelay) {
        Pattern pattern = Pattern.compile(domainPattern);
        patternLimits.put(pattern, RateLimit.of(requestsPerPeriod, period, minDelay));
        log.info("Rate limit for domain pattern {} set to {} requests per {}ms with {}ms minimum delay",
                domainPattern, requestsPerPeriod, period.toMillis(), minDelay.toMillis());
    }


    public void acquire(String url) {
        String domain = extractDomain(url);
        if (domain == null) {
            log.warn("Could not extract domain from URL: {}", url);
            return;
        }

        RateLimit limit = getRateLimitForDomain(domain);

        try {
            lock.lock();

            // Check and wait for minimum delay
            waitForMinimumDelay(domain, limit);

            // Check and enforce requests per period limit
            waitForRequestQuota(domain, limit);

            // Update counters and last access time
            updateAccessStats(domain);

        } finally {
            lock.unlock();
        }
    }


    private void waitForMinimumDelay(String domain, RateLimit limit) {
        Instant lastAccess = lastAccessTimes.get(domain);
        if (lastAccess != null) {
            Duration timeSinceLastAccess = Duration.between(lastAccess, Instant.now());
            long waitTime = limit.minDelay.toMillis() - timeSinceLastAccess.toMillis();

            if (waitTime > 0) {
                try {
                    log.debug("Waiting {}ms for minimum delay to domain {}", waitTime, domain);
                    TimeUnit.MILLISECONDS.sleep(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }


    private void waitForRequestQuota(String domain, RateLimit limit) {
        int currentCount = accessCounters.getOrDefault(domain, 0);
        Instant lastAccess = lastAccessTimes.get(domain);

        if (lastAccess != null && currentCount >= limit.requestsPerPeriod) {
            Duration timeSinceFirstRequest = Duration.between(lastAccess, Instant.now());
            long waitTime = limit.period.toMillis() - timeSinceFirstRequest.toMillis();

            if (waitTime > 0) {
                try {
                    log.debug("Rate limit reached for domain {}. Waiting {}ms before next request",
                            domain, waitTime);
                    TimeUnit.MILLISECONDS.sleep(waitTime);
                    // Reset counter after waiting
                    accessCounters.put(domain, 0);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                // Period elapsed, reset counter
                accessCounters.put(domain, 0);
            }
        }
    }


    private void updateAccessStats(String domain) {
        lastAccessTimes.put(domain, Instant.now());
        accessCounters.put(domain, accessCounters.getOrDefault(domain, 0) + 1);
    }


    private RateLimit getRateLimitForDomain(String domain) {
        // Check for exact domain match
        if (domainLimits.containsKey(domain)) {
            return domainLimits.get(domain);
        }

        // Check for pattern matching
        for (Map.Entry<Pattern, RateLimit> entry : patternLimits.entrySet()) {
            if (entry.getKey().matcher(domain).matches()) {
                return entry.getValue();
            }
        }

        // Fall back to default limit
        return defaultLimit;
    }



    private String extractDomain(String url) {
        try {
            return new URL(url).getHost();
        } catch (MalformedURLException e) {
            log.warn("Invalid URL: {}", url);
            return null;
        }
    }

    public void clearDomainData(String domain) {
        accessCounters.remove(domain);
        lastAccessTimes.remove(domain);
    }


    public void reset() {
        lock.lock();
        try {
            accessCounters.clear();
            lastAccessTimes.clear();
        } finally {
            lock.unlock();
        }
        log.info("Rate limiter reset");
    }

    public Duration getWaitTimeForDomain(String domain) {
        lock.lock();
        try {
            RateLimit limit = getRateLimitForDomain(domain);
            Instant lastAccess = lastAccessTimes.get(domain);
            if (lastAccess == null) {
                return Duration.ZERO;
            }

            int currentCount = accessCounters.getOrDefault(domain, 0);

            // Calculate time since last access
            Duration timeSinceLastAccess = Duration.between(lastAccess, Instant.now());

            // Check minimum delay
            long minDelayWait = limit.minDelay.toMillis() - timeSinceLastAccess.toMillis();

            // Check rate limit
            long rateLimitWait = 0;
            if (currentCount >= limit.requestsPerPeriod) {
                rateLimitWait = limit.period.toMillis() - timeSinceLastAccess.toMillis();
            }

            // Return the longer of the two waits
            return Duration.ofMillis(Math.max(minDelayWait, rateLimitWait));
        } finally {
            lock.unlock();
        }
    }


    public static class Builder {
        private final RateLimiter limiter;

        public Builder() {
            limiter = new RateLimiter();
        }

        public Builder defaultLimit(int requestsPerPeriod, Duration period) {
            limiter.setDefaultRateLimit(requestsPerPeriod, period, Duration.ofMillis(0));
            return this;
        }

        public Builder defaultLimit(int requestsPerPeriod, Duration period, Duration minDelay) {
            limiter.setDefaultRateLimit(requestsPerPeriod, period, minDelay);
            return this;
        }

        public Builder domainLimit(String domain, int requestsPerPeriod, Duration period) {
            limiter.addDomainLimit(domain, requestsPerPeriod, period);
            return this;
        }

        public Builder domainLimit(String domain, int requestsPerPeriod, Duration period, Duration minDelay) {
            limiter.addDomainLimit(domain, requestsPerPeriod, period, minDelay);
            return this;
        }

        public Builder patternLimit(String domainPattern, int requestsPerPeriod, Duration period, Duration minDelay) {
            limiter.addPatternLimit(domainPattern, requestsPerPeriod, period, minDelay);
            return this;
        }

        public RateLimiter build() {
            return limiter;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}