package com.doproject.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import org.springframework.stereotype.Component;

@Component
public class EngineMetrics {
    private final MeterRegistry registry;
    private final Counter oomErrors;
    private final Counter inferenceRetries;
    private final Counter persistentInferenceErrors;

    public EngineMetrics(MeterRegistry registry) {
        this.registry = registry;
        oomErrors = registry.counter("batch.oom.errors");
        inferenceRetries = registry.counter("batch.inference.retries");
        persistentInferenceErrors = registry.counter("batch.inference.persistent.errors");

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        Gauge.builder("batch.jvm.heap.used", memory, bean -> bean.getHeapMemoryUsage().getUsed())
                .baseUnit("bytes").register(registry);
        Gauge.builder("batch.jvm.heap.max", memory, bean -> bean.getHeapMemoryUsage().getMax())
                .baseUnit("bytes").register(registry);
    }

    public void recordOutOfMemory() {
        oomErrors.increment();
    }

    public void recordInferenceRetry() {
        inferenceRetries.increment();
    }

    public void recordPersistentInferenceError() {
        persistentInferenceErrors.increment();
    }

    public void recordRouteRequest(String route, double cost) {
        Counter.builder("batch.inference.route.requests").tags(Tags.of("route", route))
            .register(registry).increment();
        Counter.builder("batch.inference.estimated.cost").tags(Tags.of("route", route))
            .register(registry).increment(cost);
    }
}
