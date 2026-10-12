package com.nimrodtechs.ipcrsock.subscriber;

import com.nimrodtechs.ipcrsock.common.MessageReceiverInterface;
import com.nimrodtechs.ipcrsock.common.PublisherPayload;
import com.nimrodtechs.ipcrsock.common.SubscriptionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.ReactiveTypeDescriptor;
import org.springframework.core.codec.Decoder;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketStrategies;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adapted from the deterministic recovery harness. Private lifecycle entry points are accessed
 * reflectively to keep instrumentation out of production code. Timer execution is controlled
 * explicitly; request payloads never reach the transport, so no TCP server or sleeps are needed.
 */
@Timeout(20)
class SubscriberServiceRecoveryTest {
    private static final int WAIT_SECONDS = 5;

    @Test
    void concurrentRecoverableErrorsAreCoalescedWithoutTakingTheLifecycleLock() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.subscribe("aaa.*", fixture.broad);
            fixture.subscribe("zzz.*", fixture.narrow);
            Stream first = fixture.stream("aaa.*");
            Stream second = fixture.stream("zzz.*");
            CountDownLatch notifications = new CountDownLatch(2);
            fixture.timer.notificationLatch = notifications;
            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService callbacks = executor("error-producer");
            try {
                // The actual Reactor error callbacks must enqueue even while another thread holds this lock.
                synchronized (fixture.lock) {
                    Future<?> a = callbacks.submit(() -> failAtBarrier(first, start));
                    Future<?> b = callbacks.submit(() -> failAtBarrier(second, start));
                    a.get(WAIT_SECONDS, TimeUnit.SECONDS);
                    b.get(WAIT_SECONDS, TimeUnit.SECONDS);
                    await(notifications);
                    assertEquals(0, fixture.timer.recoveryCount());
                }
            } finally {
                stop(callbacks);
            }

            fixture.timer.drainNotifications();
            assertEquals(1, fixture.timer.recoveryCount());
            assertSame(fixture.sci.reSubscribeTimerTask, fixture.timer.peekRecovery());
        }
    }

    @Test
    void unsubscribeDuringRequesterPreparationPreventsAnOrphanedReplacement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.subscribe("aaa.*", fixture.broad);
            SubscriberService.SubscriptionInfo<?> obsolete = fixture.info("aaa.*");
            Object token = obsolete.streamAttempt;
            RSocketRequester previous = fixture.sci.getRSocketRequester();
            Stream previousStream = fixture.stream("aaa.*");
            fixture.requestRecovery("aaa.*");

            whilePreparing(fixture, () -> fixture.service.unsubscribe("publisher", "aaa.*", fixture.broad));

            assertSame(previous, fixture.sci.getRSocketRequester());
            assertTrue(previousStream.cancelled.get());
            assertTrue(fixture.service.clientSubscriptions.isEmpty());
            assertTrue(fixture.sci.upstreamActiveSubjects.isEmpty());
            assertNull(fixture.sci.reSubscribeTimerTask);
            assertEquals(0, fixture.payloadAttempts.size(), "No replacement stream should be subscribed");

            fixture.notifyError(obsolete, token);
            fixture.timer.drainNotifications();
            assertEquals(0, fixture.timer.recoveryCount());
        }
    }

    @Test
    void recoveryUsesCurrentCoverageAndItsInstalledHandleCanBeUnsubscribed() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.subscribe("aaa.*", fixture.broad);
            fixture.subscribe("aaa.bbb.*", fixture.narrow);
            fixture.requestRecovery("aaa.*");

            whilePreparing(fixture, () -> fixture.service.unsubscribe("publisher", "aaa.*", fixture.broad));

            assertEquals(Set.of("aaa.bbb.*"), fixture.sci.upstreamActiveSubjects);
            assertEquals(Set.of("publisher:aaa.bbb.*"), fixture.service.clientSubscriptions.keySet());
            assertEquals(List.of("aaa.bbb.*"), fixture.payloadAttempts.stream().map(a -> a.subject).toList());
            var replacement = fixture.info("aaa.bbb.*").disposable;
            assertNotNull(replacement);
            assertFalse(replacement.isDisposed());
            assertNull(fixture.sci.reSubscribeTimerTask);

            fixture.service.unsubscribe("publisher", "aaa.bbb.*", fixture.narrow);
            assertTrue(replacement.isDisposed(), "Unsubscribe must own the installed replacement handle");
            assertTrue(fixture.payloadAttempts.get(0).cancelled.get());
            assertTrue(fixture.service.clientSubscriptions.isEmpty());
            assertTrue(fixture.sci.upstreamActiveSubjects.isEmpty());
        }
    }

    enum FailureStage { PREPARATION, INSTALLATION }

    @ParameterizedTest
    @EnumSource(FailureStage.class)
    void recoveryExceptionsReleaseOwnershipAndAllowASuccessfulRetry(FailureStage stage) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.subscribe("aaa.*", fixture.broad);
            fixture.requestRecovery("aaa.*");
            TimerTask original = fixture.timer.takeRecovery();
            IllegalStateException failure = new IllegalStateException("Injected " + stage + " failure");
            if (stage == FailureStage.PREPARATION) {
                fixture.preparationHook.set(() -> { throw failure; });
            } else {
                fixture.installationFailure.set(failure);
            }

            assertDoesNotThrow(original::run);
            assertEquals(1, fixture.timer.recoveryCount());
            assertNotSame(original, fixture.sci.reSubscribeTimerTask);
            assertSame(fixture.timer.peekRecovery(), fixture.sci.reSubscribeTimerTask);

            assertDoesNotThrow(fixture.timer::runRecovery);
            assertNull(fixture.sci.reSubscribeTimerTask);
            assertEquals(0, fixture.timer.recoveryCount());
            assertNotNull(fixture.info("aaa.*").disposable);
            assertFalse(fixture.info("aaa.*").disposable.isDisposed());
            assertEquals(Set.of("aaa.*"), fixture.sci.upstreamActiveSubjects);
        }
    }

    @Test
    void immediateReplacementFailureIsProcessedAfterRecoveryFinishesAndObsoleteAttemptsAreIgnored()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.subscribe("aaa.*", fixture.broad);
            SubscriberService.SubscriptionInfo<?> info = fixture.info("aaa.*");
            Object originalAttempt = info.streamAttempt;
            fixture.requestRecovery("aaa.*");
            TimerTask originalTask = fixture.timer.peekRecovery();
            CountDownLatch notificationQueued = new CountDownLatch(1);
            fixture.timer.notificationLatch = notificationQueued;
            fixture.replacementFailure.set(notificationQueued);

            fixture.timer.runRecovery();

            Object failedReplacementAttempt = info.streamAttempt;
            assertNotSame(originalAttempt, failedReplacementAttempt);
            assertTrue(info.disposable.isDisposed());
            assertNull(fixture.sci.reSubscribeTimerTask);
            assertEquals(0, fixture.timer.recoveryCount(), "Error callback must only enqueue a notification");
            assertEquals(1, fixture.timer.notificationCount());
            assertSame(originalTask, fixture.ownerWhenErrorWasQueued.get(),
                    "Replacement error must arrive while the current recovery still owns its state");

            fixture.timer.drainNotifications();
            assertEquals(1, fixture.timer.recoveryCount());
            assertNotSame(originalTask, fixture.sci.reSubscribeTimerTask);
            fixture.timer.runRecovery();
            assertNull(fixture.sci.reSubscribeTimerTask);
            assertFalse(info.disposable.isDisposed());
            assertNotSame(failedReplacementAttempt, info.streamAttempt);

            fixture.notifyError(info, originalAttempt);
            fixture.notifyError(info, failedReplacementAttempt);
            fixture.timer.drainNotifications();
            assertEquals(0, fixture.timer.recoveryCount(), "Old attempts must not request another recovery");
            assertNull(fixture.sci.reSubscribeTimerTask);
        }
    }

    @Test
    void cancelledRegistrationCannotRequestRecoveryAfterTheSameSubjectIsRegisteredAgain() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.subscribe("aaa.*", fixture.broad);
            SubscriberService.SubscriptionInfo<?> obsolete = fixture.info("aaa.*");
            Object obsoleteAttempt = obsolete.streamAttempt;
            fixture.service.unsubscribe("publisher", "aaa.*", fixture.broad);
            fixture.subscribe("aaa.*", fixture.broad);
            assertNotSame(obsolete, fixture.info("aaa.*"));

            fixture.notifyError(obsolete, obsoleteAttempt);
            fixture.timer.drainNotifications();
            assertEquals(0, fixture.timer.recoveryCount());
            assertNull(fixture.sci.reSubscribeTimerTask);

            fixture.requestRecovery("aaa.*");
            assertEquals(1, fixture.timer.recoveryCount(), "Current registration must still be able to recover");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void overlappingWildcardsConsolidateInEitherOrderAndPromoteAfterBroadUnsubscribe(boolean broadFirst)
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            RecordingListener broad = new RecordingListener(1);
            RecordingListener narrow = new RecordingListener(2);
            if (broadFirst) {
                fixture.subscribe("aaa.*", broad);
                fixture.subscribe("aaa.bbb.*", narrow);
            } else {
                fixture.subscribe("aaa.bbb.*", narrow);
                fixture.subscribe("aaa.*", broad);
            }

            assertEquals(Set.of("aaa.*"), fixture.sci.upstreamActiveSubjects);
            assertEquals(Set.of("publisher:aaa.*"), fixture.service.clientSubscriptions.keySet());
            assertEquals(1, fixture.activeStreams());
            assertEquals(1, fixture.sci.wildcardListeners.get("aaa.").listeners.size());
            assertEquals(1, fixture.sci.wildcardListeners.get("aaa.bbb.").listeners.size());

            fixture.stream("aaa.*").publish("aaa.bbb.ccc", "before-promotion");
            await(broad.firstMessage);
            await(narrow.firstMessage);
            assertEquals(List.of("aaa.bbb.ccc:before-promotion"), broad.messages);
            assertEquals(List.of("aaa.bbb.ccc:before-promotion"), narrow.messages);

            fixture.service.unsubscribe("publisher", "aaa.*", broad);
            assertEquals(Set.of("aaa.bbb.*"), fixture.sci.upstreamActiveSubjects);
            assertEquals(Set.of("publisher:aaa.bbb.*"), fixture.service.clientSubscriptions.keySet());
            assertEquals(1, fixture.activeStreams());
            fixture.stream("aaa.bbb.*").publish("aaa.bbb.ddd", "after-promotion");
            await(narrow.expectedMessages);
            assertEquals(List.of("aaa.bbb.ccc:before-promotion"), broad.messages);
            assertEquals(List.of("aaa.bbb.ccc:before-promotion", "aaa.bbb.ddd:after-promotion"), narrow.messages);

            fixture.service.unsubscribe("publisher", "aaa.bbb.*", narrow);
            assertEquals(0, fixture.activeStreams());
            assertTrue(fixture.service.clientSubscriptions.isEmpty());
            assertTrue(fixture.sci.upstreamActiveSubjects.isEmpty());
        }
    }

    @Test
    void trailingWildcardDoesNotSuppressOrDispatchItsExactBaseSubject() throws Exception {
        try (Fixture fixture = new Fixture()) {
            RecordingListener exact = new RecordingListener(1);
            RecordingListener wildcard = new RecordingListener(1);
            fixture.subscribe("aaa.bbb", exact);
            fixture.subscribe("aaa.bbb.*", wildcard);
            assertEquals(Set.of("aaa.bbb", "aaa.bbb.*"), fixture.sci.upstreamActiveSubjects);

            // Both signals share one stream worker: completion of the second dispatch orders the first.
            fixture.stream("aaa.bbb.*").publish("aaa.bbb", "base");
            fixture.stream("aaa.bbb.*").publish("aaa.bbb.ccc", "descendant");
            await(exact.expectedMessages);
            await(wildcard.expectedMessages);
            assertEquals(List.of("aaa.bbb:base"), exact.messages);
            assertEquals(List.of("aaa.bbb.ccc:descendant"), wildcard.messages);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentBroadAndNarrowRegistrationMaintainsOneOwnedUpstreamStream(boolean broadFirst)
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            RecordingListener broad = new RecordingListener(1);
            RecordingListener narrow = new RecordingListener(1);
            CheckedAction registerBroad = () -> fixture.subscribe("aaa.*", broad);
            CheckedAction registerNarrow = () -> fixture.subscribe("aaa.bbb.*", narrow);

            runWithLifecycleContention(fixture,
                    broadFirst ? registerBroad : registerNarrow,
                    broadFirst ? registerNarrow : registerBroad);

            assertEquals(Set.of("aaa.", "aaa.bbb."), fixture.sci.wildcardListeners.keySet());
            assertEquals(1, fixture.sci.wildcardListeners.get("aaa.").listeners.size());
            assertSame(broad, fixture.sci.wildcardListeners.get("aaa.").listeners.get(0).messageReceiver);
            assertEquals(1, fixture.sci.wildcardListeners.get("aaa.bbb.").listeners.size());
            assertSame(narrow, fixture.sci.wildcardListeners.get("aaa.bbb.").listeners.get(0).messageReceiver);
            assertEquals(Set.of("aaa.*"), fixture.sci.upstreamActiveSubjects);
            assertEquals(Set.of("publisher:aaa.*"), fixture.service.clientSubscriptions.keySet());
            assertEquals(1, fixture.activeStreams());
            assertTrue(fixture.streams.stream().filter(s -> s.subject.equals("aaa.bbb.*"))
                    .allMatch(s -> s.cancelled.get()), "No redundant narrow stream may remain active");
            var ownedHandle = fixture.info("aaa.*").disposable;
            assertNotNull(ownedHandle);
            assertFalse(ownedHandle.isDisposed());

            fixture.stream("aaa.*").publish("aaa.bbb.ccc", "concurrent-registration");
            await(broad.expectedMessages);
            await(narrow.expectedMessages);
            assertEquals(List.of("aaa.bbb.ccc:concurrent-registration"), broad.messages);
            assertEquals(List.of("aaa.bbb.ccc:concurrent-registration"), narrow.messages);

            fixture.service.unsubscribe("publisher", "aaa.bbb.*", narrow);
            assertSame(ownedHandle, fixture.info("aaa.*").disposable);
            assertEquals(1, fixture.activeStreams());
            fixture.service.unsubscribe("publisher", "aaa.*", broad);
            assertTrue(ownedHandle.isDisposed());
            assertTrue(fixture.streams.stream().allMatch(s -> s.cancelled.get()),
                    "Every created stream must be cancellable through its recorded owner");
            assertTrue(fixture.service.clientSubscriptions.isEmpty());
            assertTrue(fixture.sci.upstreamActiveSubjects.isEmpty());
            assertTrue(fixture.sci.wildcardListeners.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentFinalListenerRemovalAndAdditionRetainsTheNewListener(boolean removalFirst)
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            String subject = "aaa.bbb";
            RecordingListener removed = new RecordingListener(0);
            RecordingListener added = new RecordingListener(1);
            fixture.subscribe(subject, removed);
            Stream originalStream = fixture.stream(subject);
            var originalHandle = fixture.info(subject).disposable;
            CheckedAction remove = () -> fixture.service.unsubscribe("publisher", subject, removed);
            CheckedAction add = () -> fixture.subscribe(subject, added);

            runWithLifecycleContention(fixture, removalFirst ? remove : add, removalFirst ? add : remove);

            assertEquals(Set.of(subject), fixture.sci.subjectListeners.keySet());
            var local = fixture.sci.subjectListeners.get(subject);
            assertEquals(1, local.listeners.size());
            assertSame(added, local.listeners.get(0).messageReceiver,
                    "The new listener must be attached to the map-reachable subscription");
            assertEquals(Set.of(subject), fixture.sci.upstreamActiveSubjects);
            assertEquals(Set.of("publisher:" + subject), fixture.service.clientSubscriptions.keySet());
            assertEquals(1, fixture.activeStreams());
            var ownedHandle = fixture.info(subject).disposable;
            assertNotNull(ownedHandle);
            assertFalse(ownedHandle.isDisposed());
            if (removalFirst) {
                assertTrue(originalStream.cancelled.get());
                assertTrue(originalHandle.isDisposed());
                assertNotSame(originalHandle, ownedHandle);
            } else {
                assertFalse(originalStream.cancelled.get());
                assertSame(originalHandle, ownedHandle);
            }

            // Nothing was published before both callers completed, so there are no queued old callbacks.
            fixture.stream(subject).publish(subject, "after-listener-change");
            await(added.expectedMessages);
            assertEquals(List.of("aaa.bbb:after-listener-change"), added.messages);
            assertTrue(removed.messages.isEmpty());

            fixture.service.unsubscribe("publisher", subject, added);
            assertTrue(ownedHandle.isDisposed());
            assertTrue(fixture.streams.stream().allMatch(s -> s.cancelled.get()),
                    "No overwritten or orphaned stream may survive final unsubscribe");
            assertTrue(fixture.sci.subjectListeners.isEmpty());
            assertTrue(fixture.service.clientSubscriptions.isEmpty());
            assertTrue(fixture.sci.upstreamActiveSubjects.isEmpty());
        }
    }

    private static void runWithLifecycleContention(Fixture fixture, CheckedAction first, CheckedAction second)
            throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Thread> owner = new AtomicReference<>();
        AtomicReference<Thread> contender = new AtomicReference<>();
        ExecutorService callers = executor("subscription-caller");
        try {
            Future<?> firstCall = callers.submit(() -> {
                // Test instrumentation chooses the first owner; the public operation reenters this monitor.
                synchronized (fixture.lock) {
                    owner.set(Thread.currentThread());
                    ready.countDown();
                    start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    await(releaseFirst);
                    first.run();
                }
                return null;
            });
            Future<?> secondCall = callers.submit(() -> {
                contender.set(Thread.currentThread());
                ready.countDown();
                start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                second.run();
                return null;
            });

            await(ready);
            var threads = ManagementFactory.getThreadMXBean();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            // Wait for an observed monitor condition, not an elapsed delay. The timeout only bounds failure.
            while (true) {
                ThreadInfo state = threads.getThreadInfo(contender.get().threadId());
                if (state != null && state.getThreadState() == Thread.State.BLOCKED
                        && state.getLockOwnerId() == owner.get().threadId()
                        && state.getLockInfo() != null
                        && state.getLockInfo().getIdentityHashCode() == System.identityHashCode(fixture.lock)) {
                    break;
                }
                assertFalse(secondCall.isDone(), "Second operation bypassed lifecycle serialization");
                assertTrue(System.nanoTime() < deadline, "Second caller did not contend for the lifecycle lock");
                Thread.onSpinWait();
            }
            releaseFirst.countDown();
            firstCall.get(WAIT_SECONDS, TimeUnit.SECONDS);
            secondCall.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            stop(callers);
        }
    }

    private static void failAtBarrier(Stream stream, CyclicBarrier barrier) {
        try {
            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(Sinks.EmitResult.OK, stream.sink.tryEmitError(new ClosedChannelException()));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static void whilePreparing(Fixture fixture, CheckedAction changes) throws Exception {
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fixture.preparationHook.set(() -> {
            assertFalse(Thread.holdsLock(fixture.lock));
            preparing.countDown();
            await(release);
        });
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "recovery-test"));
        try {
            Future<?> recovery = worker.submit(fixture.timer::runRecovery);
            try {
                await(preparing);
                changes.run();
            } finally {
                release.countDown();
            }
            recovery.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            stop(worker);
        }
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }

    private static ExecutorService executor(String name) {
        return Executors.newFixedThreadPool(2, r -> new Thread(r, name));
    }

    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "Test executor did not terminate");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "Barrier timeout");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static class RecordingListener implements MessageReceiverInterface<Object> {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final CountDownLatch firstMessage = new CountDownLatch(1);
        final CountDownLatch expectedMessages;

        RecordingListener(int expected) { expectedMessages = new CountDownLatch(expected); }

        @Override
        public void messageReceived(String publisher, String subject, Object payload) {
            messages.add(subject + ":" + payload);
            firstMessage.countDown();
            expectedMessages.countDown();
        }
    }

    private static class Stream {
        final String subject;
        final Sinks.Many<PublisherPayload<?>> sink = Sinks.many().unicast().onBackpressureBuffer();
        final AtomicBoolean cancelled = new AtomicBoolean();

        Stream(String subject) { this.subject = subject; }

        Flux<PublisherPayload<?>> flux() { return sink.asFlux().doOnCancel(() -> cancelled.set(true)); }

        void publish(String subject, Object data) {
            assertEquals(Sinks.EmitResult.OK, sink.tryEmitNext(new PublisherPayload<>(0, subject, data)));
        }
    }

    /** A serial timer queue driven by tests, preserving notification-before/after-task ordering. */
    private static class ManualTimer extends Timer {
        private final Deque<TimerTask> notifications = new ArrayDeque<>();
        private final Deque<TimerTask> recoveries = new ArrayDeque<>();
        private final Set<TimerTask> scheduled = Collections.newSetFromMap(new IdentityHashMap<>());
        volatile CountDownLatch notificationLatch;
        volatile Runnable notificationHook;
        private boolean cancelled;

        ManualTimer() { super("controlled-recovery-timer", true); }

        @Override
        public synchronized void schedule(TimerTask task, long delay) {
            if (cancelled) throw new IllegalStateException("Timer is cancelled");
            assertTrue(scheduled.add(task), "A TimerTask was scheduled more than once");
            if (delay == 0) {
                notifications.addLast(task);
                if (notificationHook != null) notificationHook.run();
                if (notificationLatch != null) notificationLatch.countDown();
            } else {
                assertEquals(2000, delay);
                recoveries.addLast(task);
            }
        }

        @Override
        public synchronized void cancel() {
            cancelled = true;
            notifications.clear();
            recoveries.clear();
            super.cancel();
        }

        void drainNotifications() {
            while (true) {
                TimerTask task;
                synchronized (this) { task = notifications.pollFirst(); }
                if (task == null) return;
                task.run();
            }
        }

        synchronized TimerTask takeRecovery() { return recoveries.removeFirst(); }
        synchronized TimerTask peekRecovery() { return recoveries.peekFirst(); }
        synchronized int recoveryCount() { return recoveries.size(); }
        synchronized int notificationCount() { return notifications.size(); }
        void runRecovery() { takeRecovery().run(); }
    }

    private static class PayloadAttempt {
        final String subject;
        final AtomicBoolean cancelled = new AtomicBoolean();
        PayloadAttempt(String subject) { this.subject = subject; }
    }

    private static class Fixture implements AutoCloseable {
        final SubscriberService service = new SubscriberService();
        final SubscriberService.SubscriberConnectionInfo sci =
                new SubscriberService.SubscriberConnectionInfo("publisher", "127.0.0.1", 1);
        final ManualTimer timer = new ManualTimer();
        final MessageReceiverInterface<Object> broad = new RecordingListener(0);
        final MessageReceiverInterface<Object> narrow = new RecordingListener(0);
        final List<Stream> streams = new CopyOnWriteArrayList<>();
        final List<PayloadAttempt> payloadAttempts = new CopyOnWriteArrayList<>();
        final AtomicReference<Runnable> preparationHook = new AtomicReference<>();
        final AtomicReference<RuntimeException> installationFailure = new AtomicReference<>();
        final AtomicReference<CountDownLatch> replacementFailure = new AtomicReference<>();
        final AtomicReference<TimerTask> ownerWhenErrorWasQueued = new AtomicReference<>();
        final AtomicBoolean initialRequesterDisposed = new AtomicBoolean();
        final Object lock;

        Fixture() throws Exception {
            lock = field(service, "lifecycleLock");
            connections(service).put("publisher", sci);
            service.subscriberProcessName = "test-subscriber";
            sci.reSubscribeTimer.cancel();
            sci.reSubscribeTimer = timer;
            timer.notificationHook = () -> ownerWhenErrorWasQueued.set(sci.reSubscribeTimerTask);
            sci.setrSocketRequester(proxy(RSocketRequester.class, (p, method, args) -> switch (method.getName()) {
                case "route" -> requestSpec();
                case "dispose" -> { initialRequesterDisposed.set(true); yield null; }
                case "isDisposed" -> initialRequesterDisposed.get();
                default -> throw new UnsupportedOperationException(method.getName());
            }));

            // RSocket resolves its connection only after a request payload is available. Withholding
            // that payload exercises real requester/stream setup without opening any TCP connection.
            ReactiveAdapterRegistry adapters = new ReactiveAdapterRegistry();
            adapters.registerReactiveType(ReactiveTypeDescriptor.singleOptionalValue(
                            SubscriptionRequest.class, SubscriptionRequest::new),
                    request -> payload((SubscriptionRequest) request), publisher -> {
                        throw new UnsupportedOperationException("Tests never adapt a publisher back to a request");
                    });
            MimeType mime = MimeType.valueOf("application/x-kryo");
            Decoder<?> decoder = proxy(Decoder.class, (p, method, args) -> switch (method.getName()) {
                case "canDecode" -> mime.equals(args[1]);
                case "getDecodableMimeTypes" -> List.of(mime);
                default -> throw new UnsupportedOperationException("No response data is decoded in recovery tests");
            });
            RSocketStrategies delegate = RSocketStrategies.builder()
                    .decoder(decoder).reactiveAdapterStrategy(adapters).build();
            service.rSocketStrategies = proxy(RSocketStrategies.class, (p, method, args) -> {
                if (method.getName().equals("dataBufferFactory")) {
                    Runnable hook = preparationHook.getAndSet(null);
                    if (hook != null) {
                        assertFalse(Thread.holdsLock(lock), "Requester preparation must be outside the lifecycle lock");
                        hook.run();
                    }
                }
                if (method.getName().equals("decoder")) {
                    assertTrue(Thread.holdsLock(lock), "Stream setup must retain lifecycle ownership");
                    RuntimeException failure = installationFailure.getAndSet(null);
                    if (failure != null) throw failure;
                }
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException ex) {
                    throw ex.getCause();
                }
            });
        }

        private Mono<Object> payload(SubscriptionRequest request) {
            return Mono.defer(() -> {
                CountDownLatch queued = replacementFailure.getAndSet(null);
                if (queued != null) {
                    // Wait only in test instrumentation: ensure the real publishOn error callback
                    // enqueues while recovery is still inside installation, before it clears ownership.
                    return Mono.<Object>error(new ClosedChannelException()).doAfterTerminate(() -> await(queued));
                }
                PayloadAttempt attempt = new PayloadAttempt(request.getSubject());
                payloadAttempts.add(attempt);
                return Mono.never().doOnCancel(() -> attempt.cancelled.set(true));
            });
        }

        private RSocketRequester.RequestSpec requestSpec() {
            AtomicReference<SubscriptionRequest> request = new AtomicReference<>();
            return proxy(RSocketRequester.RequestSpec.class, (p, method, args) -> switch (method.getName()) {
                case "data" -> { request.set((SubscriptionRequest) args[0]); yield p; }
                case "send" -> Mono.empty();
                case "retrieveFlux" -> {
                    Stream stream = new Stream(request.get().getSubject());
                    streams.add(stream);
                    yield stream.flux();
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        void subscribe(String subject, MessageReceiverInterface<Object> listener) throws Exception {
            service.subscribe("publisher", subject, listener, Object.class, false);
        }

        SubscriberService.SubscriptionInfo<?> info(String subject) {
            return service.clientSubscriptions.get("publisher:" + subject);
        }

        Stream stream(String subject) {
            return streams.stream().filter(s -> s.subject.equals(subject) && !s.cancelled.get())
                    .reduce((first, last) -> last).orElseThrow();
        }

        long activeStreams() { return streams.stream().filter(s -> !s.cancelled.get()).count(); }

        void requestRecovery(String subject) throws Exception {
            SubscriberService.SubscriptionInfo<?> info = info(subject);
            notifyError(info, info.streamAttempt);
            timer.drainNotifications();
        }

        void notifyError(SubscriberService.SubscriptionInfo<?> info, Object attempt) throws Exception {
            Method error = SubscriberService.class.getDeclaredMethod("handleFluxError",
                    SubscriberService.SubscriberConnectionInfo.class, SubscriberService.SubscriptionInfo.class,
                    Object.class, Throwable.class);
            error.setAccessible(true);
            error.invoke(service, sci, info, attempt, new ClosedChannelException());
        }

        @Override
        public void close() throws InterruptedException {
            timer.cancel();
            for (SubscriberService.SubscriptionInfo<?> info : service.clientSubscriptions.values()) {
                if (info.disposable != null) info.disposable.dispose();
            }
            sci.getRSocketRequester().dispose();
            sci.conflatingQueueExecutor.dispose();
            sci.sequentialQueueExecutor.dispose();
            assertTrue(sci.conflatingQueueExecutor.serviceThreads.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(sci.sequentialQueueExecutor.serviceThreads.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
        }
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, SubscriberService.SubscriberConnectionInfo> connections(SubscriberService service)
            throws Exception {
        return (Map<String, SubscriberService.SubscriberConnectionInfo>) field(service, "subscriberInfoMap");
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
