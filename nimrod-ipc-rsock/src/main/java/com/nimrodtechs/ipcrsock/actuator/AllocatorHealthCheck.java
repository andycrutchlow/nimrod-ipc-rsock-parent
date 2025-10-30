package com.nimrodtechs.ipcrsock.actuator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocatorMetric;
import io.netty.buffer.PoolArenaMetric;
import io.netty.buffer.PoolChunkListMetric;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodically logs Netty allocator state as a single concise line per snapshot.
 * Designed for use with actuator toggles and production log ingestion.
 */
public final class AllocatorHealthCheck {
    private static final Logger log = LoggerFactory.getLogger(AllocatorHealthCheck.class);
    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> task;

    private AllocatorHealthCheck() {}

    public static synchronized String start(long intervalSeconds) {
        if (scheduler != null && !scheduler.isShutdown()) {
            return "AllocatorHealthCheck already running";
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AllocatorHealthCheck");
            t.setDaemon(true);
            return t;
        });

        task = scheduler.scheduleAtFixedRate(AllocatorHealthCheck::logAllocatorState,
                5, intervalSeconds, TimeUnit.SECONDS);

        log.info("AllocatorHealthCheck started (interval={}s)", intervalSeconds);
        return "AllocatorHealthCheck started (interval=" + intervalSeconds + "s)";
    }

    public static synchronized String stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
            task = null;
            log.info("AllocatorHealthCheck stopped");
            return "AllocatorHealthCheck stopped";
        }
        return "AllocatorHealthCheck not running";
    }

    public static boolean isRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }

    private static void logAllocatorState() {
        try {
            PooledByteBufAllocatorMetric metric =
                    (PooledByteBufAllocatorMetric) PooledByteBufAllocator.DEFAULT.metric();

            long used = metric.usedDirectMemory();
            int arenas = metric.numDirectArenas();

            StringJoiner arenaSummary = new StringJoiner(" | ");
            int idx = 0;
            for (PoolArenaMetric arena : metric.directArenas()) {
                arenaSummary.add(String.format("A%d act=%d alloc=%d free=%d",
                        idx++, arena.numActiveAllocations(),
                        arena.numAllocations(), arena.numDeallocations()));
            }

            log.info("Allocator snapshot: DirectMemory={} bytes ({:.2f} MB), Arenas={} [{}]",
                    used, used / 1024.0 / 1024.0, arenas, arenaSummary);
        } catch (Throwable t) {
            log.warn("AllocatorHealthCheck encountered an error while logging metrics", t);
        }
    }


    public static synchronized String snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Map<String, Object>> arenasList = new ArrayList<>();

        try {
            PooledByteBufAllocatorMetric metric =
                    (PooledByteBufAllocatorMetric) PooledByteBufAllocator.DEFAULT.metric();

            long used = metric.usedDirectMemory();
            int arenas = metric.numDirectArenas();

            int idx = 0;
            for (PoolArenaMetric arena : metric.directArenas()) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("id", idx++);
                a.put("activeAllocations", arena.numActiveAllocations());
                a.put("allocations", arena.numAllocations());
                a.put("deallocations", arena.numDeallocations());
                arenasList.add(a);
            }

            root.put("timestamp", java.time.Instant.now().toString());
            root.put("directMemoryBytes", used);
            root.put("directMemoryMB", used / 1024.0 / 1024.0);
            root.put("numArenas", arenas);
            root.put("arenas", arenasList);

            // Log concise one-line summary
            StringJoiner summary = new StringJoiner(" | ");
            for (Map<String, Object> a : arenasList) {
                summary.add(String.format("A%s act=%s alloc=%s free=%s",
                        a.get("id"), a.get("activeAllocations"), a.get("allocations"), a.get("deallocations")));
            }

            log.info("Allocator snapshot: DirectMemory={} bytes ({:.2f} MB), Arenas={} [{}]",
                    used, used / 1024.0 / 1024.0, arenas, summary);

            // Convert to JSON string
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);

        } catch (Throwable t) {
            log.warn("AllocatorHealthCheck snapshot failed", t);
            return "{\"error\": \"" + t.getMessage() + "\"}";
        }
    }
}