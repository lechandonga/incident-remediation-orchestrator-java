package com.github.lechandonga.incidentremediationorchestratorjava.executor;

import com.github.lechandonga.incidentremediationorchestratorjava.model.ActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内置模拟运维执行器，替代真实运维动作。
 *
 * <p>关键语义（保证重试不会造成动作重复生效）：
 * <ul>
 *   <li>幂等键为 operationKey（建议使用 remediationId:stepId）。</li>
 *   <li>同一键上的并发调用共享同一个在途执行，后到者拿到同一结果。</li>
 *   <li>成功结果会被缓存：之后的任何重放/重试直接返回 {@code duplicate=true}，
 *       不再产生副作用。</li>
 *   <li>超时后迟到的真实完成会被丢弃，副作用不落库。</li>
 *   <li>TRANSIENT_ERROR / TIMEOUT 均在副作用提交之前发生，重试安全。</li>
 * </ul>
 */
@Component
public class MockOpsExecutor {
    private static final Logger log = LoggerFactory.getLogger(MockOpsExecutor.class);

    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mock-executor");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, FaultScript> scripts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OperationState> states = new ConcurrentHashMap<>();
    /** 每个幂等键实际生效的副作用次数，用于测试断言“无重复生效”。 */
    private final ConcurrentHashMap<String, AtomicInteger> effectCountByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ActionType, AtomicInteger> effectCountByAction = new ConcurrentHashMap<>();

    public void injectFault(String operationKey, FaultScript script) {
        scripts.put(operationKey, script);
    }

    public int effectCount(String operationKey) {
        return effectCountByKey.getOrDefault(operationKey, new AtomicInteger()).get();
    }

    public int effectCount(ActionType action) {
        return effectCountByAction.getOrDefault(action, new AtomicInteger()).get();
    }

    /**
     * 执行（或重放）一个幂等操作。
     *
     * @param operationKey 幂等键，相同键的副作用至多生效一次
     * @param attempt      调用方尝试序号（1-based），用于选择故障脚本
     * @param action       动作类型
     * @param timeoutMillis 本次调用超时阈值
     */
    public ExecOutcome execute(String operationKey, int attempt, ActionType action, long timeoutMillis) {
        OperationState state = states.computeIfAbsent(operationKey, k -> new OperationState());
        synchronized (state) {
            if (state.succeeded) {
                return new ExecOutcome(true, true, false, false,
                        "操作已成功过，幂等重放，不重复生效: " + operationKey, 0);
            }
            if (state.inFlight != null) {
                return awaitInFlight(state, operationKey);
            }
            FaultScript script = scripts.getOrDefault(operationKey, FaultScript.plain(5));
            AttemptMode mode = script.modeOf(attempt);
            if (mode == AttemptMode.SUCCESS) {
                // 快速路径保留，但为可观察性仍记录
            }
            Future<?>[] self = new Future<?>[1];
            self[0] = pool.submit(() -> runAttempt(state, operationKey, action, mode,
                    script.latencyMillis(), self));
            state.inFlight = self[0];
            return awaitInFlight(state, operationKey, timeoutMillis, mode);
        }
    }

    private ExecOutcome awaitInFlight(OperationState state, String operationKey, long timeoutMillis, AttemptMode mode) {
        long start = System.nanoTime();
        try {
            state.inFlight.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            synchronized (state) {
                state.inFlight.cancel(true);
                state.inFlight = null;
                state.timedOut = true;
                state.notifyAll();
            }
            return new ExecOutcome(false, false, true, true,
                    "执行超时(" + timeoutMillis + "ms): " + operationKey, elapsed(start));
        } catch (Exception e) {
            synchronized (state) {
                state.inFlight = null;
                boolean retryable = mode != AttemptMode.FATAL_ERROR;
                return new ExecOutcome(false, false, false, retryable,
                        "执行异常: " + rootMessage(e), elapsed(start));
            }
        }
        synchronized (state) {
            state.inFlight = null;
            if (state.succeeded) {
                return new ExecOutcome(true, false, false, false, "执行成功", elapsed(start));
            }
            return new ExecOutcome(false, false, false, mode != AttemptMode.FATAL_ERROR,
                    "执行失败: " + (state.lastError != null ? state.lastError : "未知错误"), elapsed(start));
        }
    }

    /** 并发的第二个调用者等待在途执行完成并复用其结果。 */
    private ExecOutcome awaitInFlight(OperationState state, String operationKey) {
        synchronized (state) {
            boolean interrupted = false;
            while (state.inFlight != null && !state.succeeded) {
                try {
                    state.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (state.succeeded) {
                return new ExecOutcome(true, true, false, false,
                        "复用在途执行结果（并发去重），不重复生效: " + operationKey, 0);
            }
            return new ExecOutcome(false, true, state.timedOut, true,
                    "复用在途执行的失败结果: " + operationKey, 0);
        }
    }

    private void runAttempt(OperationState state, String key, ActionType action,
                            AttemptMode mode, long latencyMillis, Future<?>[] self) {
        try {
            switch (mode) {
                case TIMEOUT -> sleepUninterruptibly(latencyMillis * 3);
                case TRANSIENT_ERROR -> {
                    sleepQuietly(Math.min(latencyMillis, 10));
                    throw new IllegalStateException("模拟瞬时错误: " + key);
                }
                case FATAL_ERROR -> {
                    sleepQuietly(Math.min(latencyMillis, 10));
                    throw new IllegalArgumentException("模拟不可重试错误: " + key);
                }
                case SUCCESS -> {
                    sleepQuietly(latencyMillis);
                    commitEffect(state, key, action);
                }
            }
        } catch (Exception e) {
            synchronized (state) {
                state.lastError = rootMessage(e);
            }
        } finally {
            synchronized (state) {
                if (state.inFlight == self[0]) {
                    state.inFlight = null;
                }
                state.notifyAll();
            }
        }
    }

    /** 副作用唯一提交点：超时取消后迟到的提交会被拒绝。 */
    private void commitEffect(OperationState state, String key, ActionType action) {
        synchronized (state) {
            if (Thread.currentThread().isInterrupted() || state.timedOut || state.succeeded) {
                log.info("丢弃迟到/重复副作用: key={} timedOut={} succeeded={}", key, state.timedOut, state.succeeded);
                return;
            }
            state.succeeded = true;
            effectCountByKey.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            effectCountByAction.computeIfAbsent(action, a -> new AtomicInteger()).incrementAndGet();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepUninterruptibly(long millis) {
        // 超时场景：即便被 interrupt 也尝试继续占住线程，模拟“远端动作迟迟不返回”；
        // 但提交副作用前会检查中断/超时状态，所以不会重复生效。
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        boolean interrupted = false;
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(Math.max(1, deadline - System.nanoTime()) / 1_000_000L);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public void reset() {
        scripts.clear();
        states.clear();
        effectCountByKey.clear();
        effectCountByAction.clear();
    }

    public Map<String, FaultScript> scriptedKeys() {
        return Map.copyOf(scripts);
    }

    /** 每个幂等键的内部状态。 */
    private static class OperationState {
        volatile Future<?> inFlight;
        volatile boolean succeeded;
        volatile boolean timedOut;
        volatile String lastError;
    }

}
