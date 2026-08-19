package com.lifeos.taskgoal.task;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records finite-cardinality lifecycle outcomes without logging task titles, IDs, or client keys.
 *
 * <p>The correlation filter supplies the request correlation identifier through MDC and tracing;
 * this component deliberately emits only operation/outcome facts safe for operational audit.
 */
@Component
public class TaskCommandAudit {

    private static final Logger log = LoggerFactory.getLogger(TaskCommandAudit.class);

    private final MeterRegistry meterRegistry;

    public TaskCommandAudit(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void success(String operation) {
        record(operation, "success");
    }

    public void rejected(String operation) {
        record(operation, "rejected");
    }

    private void record(String operation, String outcome) {
        Counter.builder("lifeos.task.command")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
        log.atInfo().addKeyValue("event", "task_command").addKeyValue("operation", operation)
                .addKeyValue("outcome", outcome).log("Task command completed");
    }
}
