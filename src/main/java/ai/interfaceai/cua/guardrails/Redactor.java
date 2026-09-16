package ai.interfaceai.cua.guardrails;

import java.util.regex.Pattern;

/**
 * Never persist secrets or raw sensitive data into artifacts or logs (3.4).
 *
 * Two things are redacted differently:
 *  - Values typed into fields whose name/label looks like a credential
 *    (password, token, ssn, pin, cvv, account number) are replaced outright,
 *    never written to disk at all — not even in the recorded Step.value,
 *    which is why the recorder calls shouldRedactField() BEFORE persisting
 *    a step's literal value.
 *  - Free-text log lines (LLM reasoning, page text snippets) are scrubbed
 *    with pattern matching as a second line of defense, since an LLM could
 *    echo a sensitive value into its own reasoning trace.
 */
public final class Redactor {
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");
    private static final Pattern SENSITIVE_FIELD_NAME = Pattern.compile(
            "(?i)password|passwd|token|secret|ssn|pin\\b|cvv|api[_-]?key|account[_-]?number|acct[_-]?num");

    private Redactor() {}

    public static boolean isSensitiveFieldName(String fieldNameOrLabel) {
        return fieldNameOrLabel != null && SENSITIVE_FIELD_NAME.matcher(fieldNameOrLabel).find();
    }

    public static String redactValueForField(String fieldNameOrLabel, String value) {
        return isSensitiveFieldName(fieldNameOrLabel) ? "[REDACTED]" : value;
    }

    public static String scrubFreeText(String text) {
        if (text == null) return null;
        String scrubbed = SSN.matcher(text).replaceAll("[REDACTED-SSN]");
        scrubbed = CARD.matcher(scrubbed).replaceAll("[REDACTED-NUM]");
        return scrubbed;
    }
}
