package com.nimrodtechs.ipcrsock.actuator;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocatorMetric;
import io.netty.buffer.PoolArenaMetric;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Exposes Netty allocator metrics to Micrometer / Actuator / Prometheus.
 */
@Component
public class AllocatorMetricsBinder {

    private final MeterRegistry registry;

    public AllocatorMetricsBinder(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void bindAllocatorMetrics() {
        PooledByteBufAllocatorMetric metric =
                (PooledByteBufAllocatorMetric) PooledByteBufAllocator.DEFAULT.metric();

        // Global gauges
        Gauge.builder("netty.allocator.direct.memory.used.bytes", metric, PooledByteBufAllocatorMetric::usedDirectMemory)
                .description("Current Netty direct memory usage in bytes")
                .register(registry);

        Gauge.builder("netty.allocator.direct.arenas", metric, PooledByteBufAllocatorMetric::numDirectArenas)
                .description("Number of direct arenas")
                .register(registry);

        // Per-arena gauges
        int idx = 0;
        for (PoolArenaMetric arena : metric.directArenas()) {
            final int arenaIdx = idx++;
            Gauge.builder("netty.allocator.arena.active.allocations", arena, PoolArenaMetric::numActiveAllocations)
                    .tag("arena", String.valueOf(arenaIdx))
                    .description("Active direct ByteBuf allocations")
                    .register(registry);

            Gauge.builder("netty.allocator.arena.total.allocations", arena, PoolArenaMetric::numAllocations)
                    .tag("arena", String.valueOf(arenaIdx))
                    .description("Total direct ByteBuf allocations")
                    .register(registry);

            Gauge.builder("netty.allocator.arena.total.deallocations", arena, PoolArenaMetric::numDeallocations)
                    .tag("arena", String.valueOf(arenaIdx))
                    .description("Total direct ByteBuf deallocations")
                    .register(registry);
        }
    }
}