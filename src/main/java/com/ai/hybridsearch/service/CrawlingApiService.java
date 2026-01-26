package com.ai.hybridsearch.service;

import org.openqa.selenium.WebDriver;
import java.io.IOException;

public interface CrawlingApiService {
    String fetchListHtml(String url) throws IOException, InterruptedException;
    String fetchDetailHtmlWithSelenium(WebDriver driver, String url) throws IOException;
    String fetchDetailHtmlWithScrapingBee(String url) throws IOException, InterruptedException;
    byte[] fetchImage(String url) throws IOException, InterruptedException;
}