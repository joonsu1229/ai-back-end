package com.ai.hybridsearch.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "crawling")
public class CrawlingApiProperties {

    public enum DetailFetchMethod {
        SELENIUM, SCRAPINGBEE
    }

    private boolean insecureSkipTlsVerify = false; // 기본값은 false
    private DetailFetchMethod detailFetchMethod = DetailFetchMethod.SELENIUM;

    private ScraperApi scraperApi;
    private ScrapingBee scrapingBee;

    @Getter
    @Setter
    public static class ScraperApi {
        private String apiKey;
        private String apiUrl;
        private boolean jsRendering;
    }

    @Getter
    @Setter
    public static class ScrapingBee {
        private String apiKey;
        private String apiUrl;
        private boolean premiumProxy;
        private String countryCode;
    }
}