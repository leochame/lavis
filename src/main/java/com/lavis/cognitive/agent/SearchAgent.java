package com.lavis.cognitive.agent;

import com.lavis.service.search.WebSearchService;
import com.lavis.service.search.WebSearchService.SearchResult;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索子代理
 *
 * 实现深度优先的网络搜索策略：
 * - 最多 5 轮迭代搜索
 * - 置信度驱动的终止条件
 * - 自动查询优化
 * - 结果汇总提炼
 *
 * 执行流程：
 * 1. 分析原始查询，生成搜索策略
 * 2. 执行搜索，评估结果质量
 * 3. 如果信息不足，优化查询并重新搜索
 * 4. 达到置信度阈值或最大轮次后，生成最终报告
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchAgent {

    private static final int MAX_ITERATIONS = 5;
    private static final double CONFIDENCE_THRESHOLD = 0.8;

    private final WebSearchService webSearchService;

    private static final String SYSTEM_PROMPT = """
            You are a research assistant specialized in web search and information synthesis.

            Your task is to help find accurate, up-to-date information through iterative web searches.

            For each search iteration, you will:
            1. Analyze the search results
            2. Determine if the information is sufficient to answer the query
            3. If not sufficient, suggest a refined search query
            4. Rate your confidence (0.0-1.0) in the current findings

            Response format (JSON):
            {
                "confidence": 0.0-1.0,
                "findings": "Key information found so far",
                "needsMoreSearch": true/false,
                "nextQuery": "Refined query if more search needed",
                "reasoning": "Why you need more information or why current info is sufficient"
            }

            Be thorough but efficient. Stop searching when you have enough information.
            """;

    private static final String SYNTHESIS_PROMPT = """
            Based on all the search results gathered, synthesize a comprehensive answer.

            Requirements:
            - Be concise (around 200 words)
            - Include key facts and sources
            - Acknowledge any limitations or uncertainties
            - Use clear, structured formatting

            Original query: %s

            Search history:
            %s

            Provide your final synthesized answer:
            """;

    /**
     * 执行搜索任务
     *
     * @param query 原始查询
     * @param chatModel LLM 模型（用于分析和合成）
     * @return 搜索报告
     */
    public SearchReport execute(String query, ChatLanguageModel chatModel) {
        log.info("SearchAgent starting: query={}", query);

        List<SearchIteration> iterations = new ArrayList<>();
        String currentQuery = query;
        double currentConfidence = 0.0;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.info("Search iteration {}/{}: query={}", i + 1, MAX_ITERATIONS, currentQuery);

            // 执行搜索
            SearchResult searchResult = webSearchService.search(currentQuery);

            if (!searchResult.success()) {
                log.warn("Search failed: {}", searchResult.answer());
                iterations.add(new SearchIteration(
                        i + 1, currentQuery, searchResult.toSummary(),
                        0.0, "Search failed", null));
                continue;
            }

            // 分析搜索结果
            IterationAnalysis analysis = analyzeResults(
                    query, currentQuery, searchResult, iterations, chatModel);

            iterations.add(new SearchIteration(
                    i + 1, currentQuery, searchResult.toSummary(),
                    analysis.confidence(), analysis.findings(), analysis.reasoning()));

            currentConfidence = analysis.confidence();

            // 检查是否达到置信度阈值
            if (currentConfidence >= CONFIDENCE_THRESHOLD || !analysis.needsMoreSearch()) {
                log.info("Search completed: confidence={}, iterations={}", currentConfidence, i + 1);
                break;
            }

            // 更新查询
            if (analysis.nextQuery() != null && !analysis.nextQuery().isBlank()) {
                currentQuery = analysis.nextQuery();
            }
        }

        // 生成最终报告
        String finalReport = synthesizeReport(query, iterations, chatModel);

        return new SearchReport(
                query,
                finalReport,
                iterations,
                currentConfidence,
                iterations.size()
        );
    }

    /**
     * 分析搜索结果
     */
    private IterationAnalysis analyzeResults(
            String originalQuery,
            String currentQuery,
            SearchResult searchResult,
            List<SearchIteration> previousIterations,
            ChatLanguageModel chatModel) {

        StringBuilder context = new StringBuilder();
        context.append("Original query: ").append(originalQuery).append("\n");
        context.append("Current search query: ").append(currentQuery).append("\n\n");
        context.append("Search results:\n").append(searchResult.toSummary()).append("\n");

        if (!previousIterations.isEmpty()) {
            context.append("\nPrevious iterations:\n");
            for (SearchIteration iter : previousIterations) {
                context.append("- Iteration ").append(iter.iteration())
                        .append(": ").append(iter.findings()).append("\n");
            }
        }

        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(context.toString())
        );

        try {
            AiMessage response = chatModel.generate(messages).content();
            return parseAnalysisResponse(response.text());
        } catch (Exception e) {
            log.error("Failed to analyze results: {}", e.getMessage());
            return new IterationAnalysis(
                    0.5, searchResult.answer(), false, null,
                    "Analysis failed: " + e.getMessage());
        }
    }

    /**
     * 解析分析响应
     */
    private IterationAnalysis parseAnalysisResponse(String response) {
        // 简单的 JSON 解析
        double confidence = extractDouble(response, "confidence", 0.5);
        String findings = extractString(response, "findings", "No findings extracted");
        boolean needsMoreSearch = extractBoolean(response, "needsMoreSearch", false);
        String nextQuery = extractString(response, "nextQuery", null);
        String reasoning = extractString(response, "reasoning", "");

        return new IterationAnalysis(confidence, findings, needsMoreSearch, nextQuery, reasoning);
    }

    /**
     * 合成最终报告
     */
    private String synthesizeReport(
            String originalQuery,
            List<SearchIteration> iterations,
            ChatLanguageModel chatModel) {

        StringBuilder searchHistory = new StringBuilder();
        for (SearchIteration iter : iterations) {
            searchHistory.append("Iteration ").append(iter.iteration()).append(":\n");
            searchHistory.append("  Query: ").append(iter.query()).append("\n");
            searchHistory.append("  Findings: ").append(iter.findings()).append("\n");
            searchHistory.append("  Confidence: ").append(iter.confidence()).append("\n\n");
        }

        String prompt = String.format(SYNTHESIS_PROMPT, originalQuery, searchHistory);

        try {
            AiMessage response = chatModel.generate(UserMessage.from(prompt)).content();
            return response.text();
        } catch (Exception e) {
            log.error("Failed to synthesize report: {}", e.getMessage());

            // 回退：返回最后一次迭代的发现
            if (!iterations.isEmpty()) {
                SearchIteration last = iterations.get(iterations.size() - 1);
                return "Search completed with " + iterations.size() + " iterations.\n\n" +
                        "Key findings: " + last.findings();
            }

            return "Search failed to produce results.";
        }
    }

    // ==================== 辅助方法 ====================

    private double extractDouble(String json, String key, double defaultValue) {
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "\"" + key + "\"\\s*:\\s*([0-9.]+)");
            java.util.regex.Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            }
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private String extractString(String json, String key, String defaultValue) {
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
            java.util.regex.Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private boolean extractBoolean(String json, String key, boolean defaultValue) {
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "\"" + key + "\"\\s*:\\s*(true|false)");
            java.util.regex.Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                return Boolean.parseBoolean(matcher.group(1));
            }
        } catch (Exception ignored) {}
        return defaultValue;
    }

    // ==================== 数据类 ====================

    /**
     * 迭代分析结果
     */
    private record IterationAnalysis(
            double confidence,
            String findings,
            boolean needsMoreSearch,
            String nextQuery,
            String reasoning
    ) {}

    /**
     * 单次搜索迭代
     */
    public record SearchIteration(
            int iteration,
            String query,
            String rawResults,
            double confidence,
            String findings,
            String reasoning
    ) {}

    /**
     * 搜索报告
     */
    public record SearchReport(
            String originalQuery,
            String summary,
            List<SearchIteration> iterations,
            double finalConfidence,
            int totalIterations
    ) {
        public String toCompactSummary() {
            return String.format("""
                    ## Search Report

                    **Query:** %s
                    **Confidence:** %.0f%%
                    **Iterations:** %d

                    ### Summary
                    %s
                    """, originalQuery, finalConfidence * 100, totalIterations, summary);
        }
    }
}
