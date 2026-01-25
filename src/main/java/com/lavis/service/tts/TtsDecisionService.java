package com.lavis.service.tts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * TTS Decision Service
 *
 * Determines whether the user needs voice feedback based on their query.
 * This runs in parallel with LLM generation for efficiency.
 *
 * Design principles:
 * 1. Based on user intent, not LLM response content
 * 2. Fast execution (<1ms) to run in parallel with LLM
 * 3. When in doubt, prefer voice feedback (user can always skip)
 */
@Slf4j
@Service
public class TtsDecisionService {

    // Silent commands - user confirmations that don't need voice feedback
    private static final Pattern SILENT_CONFIRMATION_PATTERN = Pattern.compile(
        "^(好的?|行|可以|确认|是的?|对|嗯|ok|yes|sure|confirm|got it|okay)$",
        Pattern.CASE_INSENSITIVE
    );

    // Text-only request indicators - user explicitly wants text/code output
    private static final Set<String> TEXT_ONLY_INDICATORS = Set.of(
        "不用说", "不用读", "不要语音", "静音", "文字就行", "不用播报",
        "no voice", "no audio", "don't speak", "text only", "silent", "mute"
    );

    /**
     * Determine if user needs voice feedback
     *
     * @param userQuery User's question/command (STT transcription result)
     * @return true if voice feedback is needed, false otherwise
     */
    public boolean needsVoiceFeedback(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return false;
        }

        String query = userQuery.trim().toLowerCase();

        // 1. Silent confirmations - no voice needed
        if (SILENT_CONFIRMATION_PATTERN.matcher(query).matches()) {
            log.debug("TTS Decision: SILENT_CONFIRMATION -> false, query: {}", userQuery);
            return false;
        }

        // 2. User explicitly requests no voice
        for (String indicator : TEXT_ONLY_INDICATORS) {
            if (query.contains(indicator)) {
                log.debug("TTS Decision: TEXT_ONLY_REQUEST -> false, query: {}", userQuery);
                return false;
            }
        }

        // 3. Default: user needs voice feedback
        // The actual content (direct speech vs summary) will be determined
        // by AsyncTtsService based on LLM response
        log.debug("TTS Decision: DEFAULT -> true, query: {}", userQuery);
        return true;
    }
}
