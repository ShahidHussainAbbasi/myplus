package com.myplus.education.config;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The thread pool that delivers queued notices — slice 3.5's D3, made real on 2026-08-09.
 *
 * <h3>Why this exists</h3>
 *
 * D3 says <b>"Delivery goes through N1's outbox. Nothing sends on the request thread"</b>, and case 7 of the
 * slice's own test plan is <i>"publishing queues and does not block"</i>. The code did not honour that: the
 * AFTER_COMMIT hook ran delivery <b>inline, inside the caller's commit</b>, so a publish did one SMTP
 * round-trip per recipient before the response was written.
 *
 * <p>It was invisible until slice 105 because {@code enabled(null)} was short-circuiting every send, so
 * nothing was ever delivered and the hook cost nothing. With that fixed, a single publish was measured
 * making <b>24 sequential SMTP attempts ~1.75s apart — about 42 seconds on one request thread</b>, against
 * the gateway's 20s time limit. The circuit breaker cancelled the call and the caller got InternalError for
 * a notice that had, in fact, been queued correctly.
 *
 * <p>Note the shape of that failure: fixing the SMTP credentials would have HIDDEN it. Working credentials
 * are merely faster per send, and a whole-school notice to forty families would still have crept back up on
 * the limit — while the request thread went on doing SMTP, which is the thing D3 forbids outright.
 *
 * <h3>Why a bounded pool rather than the default</h3>
 *
 * Spring's default async executor creates threads without an upper bound. That turns a large broadcast into
 * unbounded thread growth — trading a slow request for an unstable service, which is a worse bargain. This
 * pool is deliberately small: delivery is I/O-bound on a shared SMTP sender that rate-limits anyway, so
 * more threads would buy nothing and risk the sender's reputation.
 *
 * <h3>When the queue is full: leave it to the relay, never run it here</h3>
 *
 * A rejected delivery is <b>not run</b> — {@link LeavePendingForRelay} logs it and returns. Nothing is lost:
 * the outbox row was committed PENDING before the event was published, and {@code EduNotifyService.flushPending}
 * re-drives PENDING rows every 30 s. This is load shedding over a durable outbox.
 *
 * <p>It used to be {@code CallerRunsPolicy}, on the reasoning "slow beats dropping a closure notice". But
 * nothing was ever dropped — the row is the durable copy — and CallerRuns put SMTP back on the <b>request
 * thread</b>, the exact thing D3 forbids, at the worst possible moment: the queue only fills when the mail
 * server is slow or refusing. On 2026-10-03 Gmail was refusing the dev stack's logins, 500 deliveries sat
 * queued, and every notice publish ran its recipients' SMTP attempts inline and came back InternalError past
 * the gateway's 20 s limit — for notices that were saved correctly (EDU-NOTIFY-2).
 */
@Configuration
@EnableAsync
public class NotifyAsyncConfig {

    /** Named so {@code @Async("notifyExecutor")} can never silently fall back to the unbounded default. */
    @Bean("notifyExecutor")
    public Executor notifyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("edu-notify-");
        executor.setRejectedExecutionHandler(new LeavePendingForRelay());
        // Let in-flight deliveries finish on shutdown instead of being killed mid-send, which would leave a
        // row PENDING with no error recorded — the ambiguous state slice 105 exists to eliminate.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * A full queue sheds the delivery instead of running it on the caller's thread. The notice is not lost —
     * its outbox row is already PENDING and the scheduled relay picks it up (see the class comment).
     */
    public static final class LeavePendingForRelay implements RejectedExecutionHandler {

        private static final Logger log = LoggerFactory.getLogger(LeavePendingForRelay.class);

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor pool) {
            log.warn("EDU-NOTIFY queue full ({} queued, {} active): delivery left PENDING for the scheduled relay",
                    pool.getQueue().size(), pool.getActiveCount());
        }
    }
}
