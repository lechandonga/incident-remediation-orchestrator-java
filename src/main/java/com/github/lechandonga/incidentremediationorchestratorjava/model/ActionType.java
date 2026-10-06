package com.github.lechandonga.incidentremediationorchestratorjava.model;

/**
 * 模拟运维动作类型。每个动作对应内置模拟执行器的一个幂等操作。
 */
public enum ActionType {
    RESTART_SERVICE,
    KILL_PROCESS,
    CLEAR_CACHE,
    SCALE_OUT,
    ISOLATE_HOST,
    NOTIFY_ONCALL
}
