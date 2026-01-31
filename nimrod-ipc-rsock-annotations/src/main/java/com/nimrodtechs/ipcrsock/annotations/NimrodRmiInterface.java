package com.nimrodtechs.ipcrsock.annotations;


import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface NimrodRmiInterface {
    /**
     * Logical service name used for routing, e.g. {@code "datamanager"}.
     * <p>
     * Routes are constructed as {@code "<serviceName>.<methodName>"}.
     * <p>
     * A single process / JVM may host multiple logical {@code services}, each
     * representing a related group of remote methods and sharing a common
     * server-side execution profile (concurrency, scheduler, timeout).
     * <p>
     * This allows concurrency and execution characteristics to be defined at the
     * service level rather than per-method or per-process.
     * <p>
     * For example:
     * <ul>
     *   <li>A {@code datamanager} process may expose one large service containing
     *       all remote methods.</li>
     *   <li>Alternatively, {@code datamanager} may expose multiple services such as
     *       {@code trading-entity}, {@code product}, and {@code position}.</li>
     * </ul>
     * <p>
     * In this model:
     * <ul>
     *   <li>{@code trading-entity} may be heavily used by many concurrent clients
     *       and therefore require wider concurrency.</li>
     *   <li>{@code product} may be accessed infrequently, where lower concurrency
     *       and small amounts of queuing are acceptable due to lightweight calls.</li>
     * </ul>
     */

    /**
     * Logical service name used for routing.
     * Example: "datamanager"
     */
    String serviceName();

    /**
     * Server-side execution concurrency.
     *
     * 1  = single-threaded (current behaviour)
     * >1 = bounded parallelism via generated scheduler
     */
    int concurrency() default 1;

    /**
     * Scheduler type used by generated controller.
     *
     * SINGLE preserves current semantics.
     */
    SchedulerType scheduler() default SchedulerType.SINGLE;

    /**
     * Optional server-side execution timeout in milliseconds.
     *
     * 0 = no timeout (current behaviour)
     */
    long timeoutMs() default 0;

    /**
     * Retry policy placeholder (future use).
     *
     * NONE preserves current behaviour.
     */
    RetryPolicy retryPolicy() default RetryPolicy.NONE;
}