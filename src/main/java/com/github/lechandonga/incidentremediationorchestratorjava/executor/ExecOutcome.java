package com.github.lechandonga.incidentremediationorchestratorjava.executor;

/** 模拟执行器单次调用结果。 */
public record ExecOutcome(
        boolean success,
        boolean duplicate,
        boolean timedOut,
        boolean retryable,
        String message,
        long elapsedMillis
) {
}
