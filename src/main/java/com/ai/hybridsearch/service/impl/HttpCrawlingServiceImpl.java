package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.config.CrawlingApiProperties;
import com.ai.hybridsearch.service.CrawlingApiService;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;

@Service
@Slf4j
public class HttpCrawlingServiceImpl implements CrawlingApiService {

    private final HttpClient httpClient;
    private final CrawlingApiProperties properties;

    @Autowired
    public HttpCrawlingServiceImpl(CrawlingApiProperties properties) {
        this.properties = properties;
        
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20));

        if (properties.isInsecureSkipTlsVerify()) {
            log.warn("INSECURE: Bypassing SSL/TLS certificate validation for HttpClient. DO NOT USE IN PRODUCTION.");
            clientBuilder.sslContext(createInsecureSslContext());
        }

        this.httpClient = clientBuilder.build();
    }
    
    private SSLContext createInsecureSslContext() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            return sc;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create insecure SSL context", e);
        }
    }

    @Override
    public String fetchListHtml(String url) throws IOException, InterruptedException {
        CrawlingApiProperties.ScraperApi config = properties.getScraperApi();
        if (config == null || config.getApiUrl() == null || config.getApiKey() == null) {
            throw new IOException("ScraperAPI configuration is missing in application.yml.");
        }

        StringBuilder apiUrlBuilder = new StringBuilder(config.getApiUrl());
        apiUrlBuilder.append("?api_key=").append(config.getApiKey());
        apiUrlBuilder.append("&url=").append(URLEncoder.encode(url, StandardCharsets.UTF_8));

        if (config.isJsRendering()) {
            apiUrlBuilder.append("&render=true");
        }

        return executeApiRequest(apiUrlBuilder.toString(), url, "ScraperAPI");
    }

    @Override
    public String fetchDetailHtmlWithSelenium(WebDriver driver, String url) throws IOException {
        log.info("Fetching HTML via Selenium from URL: {}", url);
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
            driver.get(url);
            wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
            String pageSource = driver.getPageSource();
            log.info("Successfully fetched HTML via Selenium. Response size: {} bytes", pageSource.length());
            return pageSource;
        } catch (Exception e) {
            log.error("Failed to fetch HTML via Selenium for URL: {}. Error: {}", url, e.getMessage());
            throw new IOException("Selenium fetch failed for URL " + url, e);
        }
    }

    @Override
    public String fetchDetailHtmlWithScrapingBee(String url) throws IOException, InterruptedException {
        CrawlingApiProperties.ScrapingBee config = properties.getScrapingBee();
        if (config == null || config.getApiUrl() == null || config.getApiKey() == null) {
            throw new IOException("ScrapingBee configuration is missing in application.yml.");
        }

        StringBuilder apiUrlBuilder = new StringBuilder(config.getApiUrl());
        apiUrlBuilder.append("?api_key=").append(config.getApiKey());
        apiUrlBuilder.append("&url=").append(URLEncoder.encode(url, StandardCharsets.UTF_8));
        apiUrlBuilder.append("&render_js=true");
        apiUrlBuilder.append("&json_response=false");

        if (config.isPremiumProxy()) {
            apiUrlBuilder.append("&premium_proxy=true");
        }
        if (config.getCountryCode() != null && !config.getCountryCode().isEmpty()) {
            apiUrlBuilder.append("&country_code=").append(config.getCountryCode());
        }

        return executeApiRequest(apiUrlBuilder.toString(), url, "ScrapingBee");
    }

    @Override
    public byte[] fetchImage(String url) throws IOException, InterruptedException {
        log.info("Fetching image directly from URL: {}", url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(1))
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .build();
        
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() >= 400) {
            log.error("Failed to fetch image. Status code: {}, URL: {}", response.statusCode(), url);
            throw new IOException("HTTP request for image failed with status " + response.statusCode());
        }
        log.info("Successfully fetched image from URL: {}. Size: {} bytes", url, response.body().length);
        return response.body();
    }

    private String executeApiRequest(String apiUrl, String originalUrl, String apiName) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofMinutes(2))
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .build();

        log.info("HTML을 {} API를 통해 가져옵니다. URL: {}", apiName, originalUrl);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            log.error("{} API를 통해 URL을 가져오는 데 실패했습니다. 상태 코드: {}, 응답: {}", apiName, response.statusCode(), response.body(), originalUrl);
            throw new IOException("HTTP request failed with status " + response.statusCode());
        }

        log.info("URL에서 HTML을 성공적으로 가져왔습니다: {}. 응답 크기: {} bytes", originalUrl, response.body().length());
        return response.body();
    }
}
