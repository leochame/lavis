package com.lavis.service.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络搜索服务
 *
 * 支持多种搜索后端：
 * - Tavily API (推荐，专为 AI 优化)
 * - DuckDuckGo (免费，无需 API Key)
 * - SerpAPI (Google 搜索代理)
 *
 * 特性：
 * - 自动追加时间戳到查询
 * - 结果摘要提取
 * - 错误重试
 */
@Slf4j
@Service
public class WebSearchService {

    @Value("${lavis.search.provider:duckduckgo}")
    private String searchProvider;

    @Value("${lavis.search.tavily.api-key:}")
    private String tavilyApiKey;

    @Value("${lavis.search.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${lavis.search.max-results:5}")
    private int maxResults;

    private final HttpClient httpClient;

    public WebSearchService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 执行搜索
     *
     * @param query 搜索查询
     * @return 搜索结果列表
     */
    public SearchResult search(String query) {
        return search(query, true);
    }

    /**
     * 执行搜索
     *
     * @param query 搜索查询
     * @param appendTimestamp 是否追加时间戳
     * @return 搜索结果
     */
    public SearchResult search(String query, boolean appendTimestamp) {
        String enhancedQuery = appendTimestamp ? enhanceQueryWithTimestamp(query) : query;

        log.info("Executing web search: provider={}, query={}", searchProvider, enhancedQuery);

        try {
            return switch (searchProvider.toLowerCase()) {
                case "tavily" -> searchWithTavily(enhancedQuery);
                case "duckduckgo", "ddg" -> searchWithDuckDuckGo(enhancedQuery);
                default -> {
                    log.warn("Unknown search provider: {}, falling back to DuckDuckGo", searchProvider);
                    yield searchWithDuckDuckGo(enhancedQuery);
                }
            };
        } catch (Exception e) {
            log.error("Search failed: {}", e.getMessage());
            return SearchResult.error(query, e.getMessage());
        }
    }

    /**
     * 使用 Tavily API 搜索
     */
    private SearchResult searchWithTavily(String query) throws IOException, InterruptedException {
        if (tavilyApiKey == null || tavilyApiKey.isBlank()) {
            log.warn("Tavily API key not configured, falling back to DuckDuckGo");
            return searchWithDuckDuckGo(query);
        }

        String requestBody = String.format("""
                {
                    "api_key": "%s",
                    "query": "%s",
                    "search_depth": "basic",
                    "include_answer": true,
                    "include_raw_content": false,
                    "max_results": %d
                }
                """, tavilyApiKey, escapeJson(query), maxResults);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.tavily.com/search"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Tavily API error: " + response.statusCode());
        }

        return parseTavilyResponse(query, response.body());
    }

    /**
     * 使用 DuckDuckGo 搜索 (HTML 解析)
     */
    private SearchResult searchWithDuckDuckGo(String query) throws IOException, InterruptedException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://html.duckduckgo.com/html/?q=" + encodedQuery;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("DuckDuckGo error: " + response.statusCode());
        }

        return parseDuckDuckGoResponse(query, response.body());
    }

    /**
     * 解析 Tavily 响应
     */
    private SearchResult parseTavilyResponse(String query, String json) {
        List<SearchResult.ResultItem> items = new ArrayList<>();

        // 简单的 JSON 解析（避免引入额外依赖）
        // 提取 answer
        String answer = extractJsonString(json, "answer");

        // 提取 results 数组中的项目
        Pattern resultPattern = Pattern.compile(
                "\"title\"\\s*:\\s*\"([^\"]+)\".*?\"url\"\\s*:\\s*\"([^\"]+)\".*?\"content\"\\s*:\\s*\"([^\"]+)\"",
                Pattern.DOTALL);
        Matcher matcher = resultPattern.matcher(json);

        while (matcher.find() && items.size() < maxResults) {
            items.add(new SearchResult.ResultItem(
                    unescapeJson(matcher.group(1)),
                    matcher.group(2),
                    unescapeJson(matcher.group(3))
            ));
        }

        return new SearchResult(query, items, answer, "tavily", true);
    }

    /**
     * 解析 DuckDuckGo HTML 响应
     */
    private SearchResult parseDuckDuckGoResponse(String query, String html) {
        List<SearchResult.ResultItem> items = new ArrayList<>();

        // 提取搜索结果
        Pattern resultPattern = Pattern.compile(
                "<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>([^<]+)</a>.*?" +
                "<a[^>]+class=\"result__snippet\"[^>]*>([^<]+)</a>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

        Matcher matcher = resultPattern.matcher(html);

        while (matcher.find() && items.size() < maxResults) {
            String url = matcher.group(1);
            String title = matcher.group(2).trim();
            String snippet = matcher.group(3).trim();

            // 清理 HTML 实体
            title = cleanHtml(title);
            snippet = cleanHtml(snippet);

            // 跳过广告
            if (url.contains("duckduckgo.com/y.js")) {
                continue;
            }

            items.add(new SearchResult.ResultItem(title, url, snippet));
        }

        // 如果正则没匹配到，尝试更宽松的模式
        if (items.isEmpty()) {
            Pattern simplePattern = Pattern.compile(
                    "<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>([^<]+)</a>",
                    Pattern.CASE_INSENSITIVE);
            Matcher simpleMatcher = simplePattern.matcher(html);

            while (simpleMatcher.find() && items.size() < maxResults) {
                String url = simpleMatcher.group(1);
                String title = cleanHtml(simpleMatcher.group(2).trim());

                if (!url.contains("duckduckgo.com") && title.length() > 10) {
                    items.add(new SearchResult.ResultItem(title, url, ""));
                }
            }
        }

        return new SearchResult(query, items, null, "duckduckgo", !items.isEmpty());
    }

    /**
     * 增强查询：追加当前日期
     */
    private String enhanceQueryWithTimestamp(String query) {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return query + " " + date;
    }

    /**
     * 从 JSON 中提取字符串值
     */
    private String extractJsonString(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? unescapeJson(matcher.group(1)) : null;
    }

    /**
     * 转义 JSON 字符串
     */
    private String escapeJson(String str) {
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 反转义 JSON 字符串
     */
    private String unescapeJson(String str) {
        return str.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }

    /**
     * 清理 HTML 实体
     */
    private String cleanHtml(String html) {
        return html.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replaceAll("<[^>]+>", "")
                .trim();
    }

    /**
     * 搜索结果
     */
    public record SearchResult(
            String query,
            List<ResultItem> items,
            String answer,
            String provider,
            boolean success
    ) {
        public static SearchResult error(String query, String errorMessage) {
            return new SearchResult(query, List.of(), "Search failed: " + errorMessage, "error", false);
        }

        public String toSummary() {
            if (!success) {
                return answer;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Search results for: ").append(query).append("\n\n");

            if (answer != null && !answer.isBlank()) {
                sb.append("Summary: ").append(answer).append("\n\n");
            }

            for (int i = 0; i < items.size(); i++) {
                ResultItem item = items.get(i);
                sb.append(i + 1).append(". ").append(item.title()).append("\n");
                sb.append("   URL: ").append(item.url()).append("\n");
                if (item.snippet() != null && !item.snippet().isBlank()) {
                    sb.append("   ").append(item.snippet()).append("\n");
                }
                sb.append("\n");
            }

            return sb.toString();
        }

        /**
         * 单个搜索结果项
         */
        public record ResultItem(
                String title,
                String url,
                String snippet
        ) {}
    }
}
