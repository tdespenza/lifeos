package com.lifeos.assistant.safety;

import com.lifeos.assistant.config.AiAssistantProperties;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Applies deterministic prompt-injection rejection and PII minimization before any provider call.
 *
 * <p>This is intentionally conservative: a suspected instruction-boundary attack is rejected,
 * rather than sent to a model to decide whether it is safe.
 */
@Service
public class AssistantPromptSafetyService {

    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern SOCIAL_SECURITY_NUMBER = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\w)\\+?\\d(?:[\\s().-]*\\d){8,14}(?!\\w)");
    private static final Pattern CARD_CANDIDATE = Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)");
    private static final Pattern PROMPT_INJECTION = Pattern.compile(
            "(?i)(ignore\\s+(all\\s+)?(previous|prior)\\s+instructions|"
                    + "system\\s+prompt|developer\\s+message|jailbreak|"
                    + "reveal\\s+(the\\s+)?(system|hidden)\\s+(prompt|instructions))");

    private final AiAssistantProperties properties;

    public AssistantPromptSafetyService(AiAssistantProperties properties) {
        this.properties = properties;
    }

    public AssistantPromptSafetyAssessment assess(String message) {
        int inputCharacters = message.codePointCount(0, message.length());
        int estimatedTokens = estimateTokens(inputCharacters);
        EnumSet<AssistantSafetyFlag> flags = EnumSet.noneOf(AssistantSafetyFlag.class);
        if (inputCharacters > properties.getMaxMessageCharacters()) {
            flags.add(AssistantSafetyFlag.INPUT_CHARACTER_LIMIT_EXCEEDED);
        }
        if (estimatedTokens > properties.getMaxEstimatedInputTokens()) {
            flags.add(AssistantSafetyFlag.INPUT_TOKEN_LIMIT_EXCEEDED);
        }
        String redacted = redactPii(message, flags);
        if (PROMPT_INJECTION.matcher(redacted).find()) {
            flags.add(AssistantSafetyFlag.PROMPT_INJECTION_SUSPECTED);
            return new AssistantPromptSafetyAssessment(
                    redacted,
                    inputCharacters,
                    estimatedTokens,
                    flags,
                    AssistantPromptDisposition.REJECT_PROMPT_INJECTION);
        }
        if (flags.contains(AssistantSafetyFlag.INPUT_CHARACTER_LIMIT_EXCEEDED)
                || flags.contains(AssistantSafetyFlag.INPUT_TOKEN_LIMIT_EXCEEDED)) {
            return new AssistantPromptSafetyAssessment(
                    redacted,
                    inputCharacters,
                    estimatedTokens,
                    flags,
                    AssistantPromptDisposition.REJECT_INPUT_LIMIT);
        }
        return new AssistantPromptSafetyAssessment(
                redacted, inputCharacters, estimatedTokens, flags, AssistantPromptDisposition.SAFE);
    }

    private static int estimateTokens(int inputCharacters) {
        // One token per two Unicode code points deliberately overestimates ordinary English while
        // remaining deterministic and independent from any provider tokenizer.
        return Math.max(1, Math.ceilDiv(inputCharacters, 2));
    }

    private static String redactPii(String message, EnumSet<AssistantSafetyFlag> flags) {
        String redacted = replaceIfPresent(EMAIL, message, "[REDACTED_EMAIL]", flags);
        redacted = replaceIfPresent(SOCIAL_SECURITY_NUMBER, redacted, "[REDACTED_SSN]", flags);
        redacted = redactPaymentCards(redacted, flags);
        return replaceIfPresent(PHONE, redacted, "[REDACTED_PHONE]", flags);
    }

    private static String replaceIfPresent(
            Pattern pattern, String value, String replacement, EnumSet<AssistantSafetyFlag> flags) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return value;
        }
        flags.add(AssistantSafetyFlag.PII_REDACTED);
        return matcher.replaceAll(replacement);
    }

    private static String redactPaymentCards(String value, EnumSet<AssistantSafetyFlag> flags) {
        Matcher matcher = CARD_CANDIDATE.matcher(value);
        StringBuffer result = new StringBuffer();
        boolean redacted = false;
        while (matcher.find()) {
            String candidate = matcher.group();
            if (isLuhnValid(candidate)) {
                matcher.appendReplacement(result, Matcher.quoteReplacement("[REDACTED_CARD]"));
                redacted = true;
            }
        }
        if (!redacted) {
            return value;
        }
        matcher.appendTail(result);
        flags.add(AssistantSafetyFlag.PII_REDACTED);
        return result.toString();
    }

    private static boolean isLuhnValid(String candidate) {
        String digits = candidate.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleDigit = false;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int value = digits.charAt(index) - '0';
            if (doubleDigit) {
                value *= 2;
                if (value > 9) {
                    value -= 9;
                }
            }
            sum += value;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }
}
