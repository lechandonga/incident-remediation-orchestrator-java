package com.github.lechandonga.incidentremediationorchestratorjava.model;

/** 处置流程中单个步骤的执行状态。 */
public enum StepState {
    /** 等待执行（尚未开始，不占用执行器副作用）。 */
    PENDING,
    /** 正在执行（模拟执行器已收到该步骤的幂等请求）。 */
    RUNNING,
    /** 步骤成功；同一 attemptId 的成功结果可被安全重放。 */
    SUCCEEDED,
    /** 步骤失败且重试已耗尽（或错误不可重试）。 */
    FAILED,
    /** 因流程取消/整体超时而未执行或中断。 */
    SKIPPED
}
