package com.github.lechandonga.incidentremediationorchestratorjava.executor;

import java.util.Map;

/**
 * 针对某个操作键的故障脚本：
 * 第 {@code attempt} 次尝试（1-based）按 attemptModes 行为执行，
 * 未配置的尝试默认成功。{@code latencyMillis} 控制模拟执行耗时。
 */
public record FaultScript(Map<Integer, AttemptMode> attemptModes, long latencyMillis) {
    public static FaultScript plain(long latencyMillis) {
        return new FaultScript(Map.of(), latencyMillis);
    }

    public AttemptMode modeOf(int attempt) {
        return attemptModes.getOrDefault(attempt, AttemptMode.SUCCESS);
    }
}
