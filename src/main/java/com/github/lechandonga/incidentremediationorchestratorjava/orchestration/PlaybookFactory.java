package com.github.lechandonga.incidentremediationorchestratorjava.orchestration;

import com.github.lechandonga.incidentremediationorchestratorjava.model.ActionType;
import com.github.lechandonga.incidentremediationorchestratorjava.model.StepRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 剧本工厂：把动作映射为有序步骤序列，每步带独立的重试次数与超时。
 * 重试只覆盖“瞬时错误/超时”，不可重试错误立即终止。
 */
@Component
public class PlaybookFactory {
    private final int defaultMaxRetries;
    private final long defaultStepTimeoutMillis;

    public PlaybookFactory(
            @Value("${orchestrator.step.default-max-retries:2}") int defaultMaxRetries,
            @Value("${orchestrator.step.default-timeout-millis:2000}") long defaultStepTimeoutMillis) {
        this.defaultMaxRetries = defaultMaxRetries;
        this.defaultStepTimeoutMillis = defaultStepTimeoutMillis;
    }

    public List<StepRecord> buildSteps(ActionType action) {
        List<StepRecord> steps = new ArrayList<>();
        String prefix = action.name().toLowerCase();
        switch (action) {
            case RESTART_SERVICE -> {
                steps.add(step(prefix + "-1", "停止服务", ActionType.KILL_PROCESS, defaultMaxRetries));
                steps.add(step(prefix + "-2", "健康检查", ActionType.NOTIFY_ONCALL, 0));
                steps.add(step(prefix + "-3", "拉起服务", ActionType.RESTART_SERVICE, defaultMaxRetries));
            }
            case ISOLATE_HOST -> {
                steps.add(step(prefix + "-1", "摘除流量", ActionType.SCALE_OUT, defaultMaxRetries));
                steps.add(step(prefix + "-2", "网络隔离", ActionType.ISOLATE_HOST, 0));
            }
            default ->
                // KILL_PROCESS / CLEAR_CACHE / SCALE_OUT / NOTIFY_ONCALL：单步剧本
                steps.add(step(prefix + "-1", "执行动作 " + action, action, defaultMaxRetries));
        }
        return steps;
    }

    private StepRecord step(String id, String name, ActionType action, int maxRetries) {
        return new StepRecord(id, name, action, maxRetries, defaultStepTimeoutMillis);
    }

    public String newRemediationId() {
        return "RM-" + UUID.randomUUID();
    }
}
