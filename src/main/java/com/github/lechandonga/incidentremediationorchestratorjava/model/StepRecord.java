package com.github.lechandonga.incidentremediationorchestratorjava.model;

import java.util.ArrayList;
import java.util.List;

/** 步骤运行态记录。所有字段仅在持有所属 Remediation 锁时访问。 */
public class StepRecord {
    private final String stepId;
    private final String name;
    private final ActionType action;
    private final int maxRetries;
    private final long timeoutMillis;
    private StepState state = StepState.PENDING;
    private int attempts = 0;
    private final List<StepResult> results = new ArrayList<>();
    private String lastError;

    public StepRecord(String stepId, String name, ActionType action, int maxRetries, long timeoutMillis) {
        this.stepId = stepId;
        this.name = name;
        this.action = action;
        this.maxRetries = maxRetries;
        this.timeoutMillis = timeoutMillis;
    }

    public String stepId() { return stepId; }
    public String name() { return name; }
    public ActionType action() { return action; }
    public int maxRetries() { return maxRetries; }
    public long timeoutMillis() { return timeoutMillis; }
    public StepState state() { return state; }
    public void state(StepState s) { this.state = s; }
    public int attempts() { return attempts; }
    public void incrementAttempts() { this.attempts++; }
    public List<StepResult> results() { return results; }
    public String lastError() { return lastError; }
    public void lastError(String e) { this.lastError = e; }
}
