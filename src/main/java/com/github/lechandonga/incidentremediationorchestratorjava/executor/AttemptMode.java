package com.github.lechandonga.incidentremediationorchestratorjava.executor;

/** 模拟执行器在某次尝试上的预设行为，用于测试注入失败与超时。 */
public enum AttemptMode {
    SUCCESS,
    /** 抛出瞬时错误（未产生副作用），步骤可按策略重试。 */
    TRANSIENT_ERROR,
    /** 抛出不可重试错误，步骤立即失败并终止流程。 */
    FATAL_ERROR,
    /** 执行耗时超过调用方超时阈值，触发超时路径。 */
    TIMEOUT
}
