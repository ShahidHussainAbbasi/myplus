package com.myplus.education.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

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
 * <p>{@code CallerRunsPolicy} is the deliberate back-pressure choice. If the queue ever fills, the work is
 * done on the calling thread rather than discarded — slow beats silently dropping a school's closure
 * notice. The queue is sized so that is a genuinely exceptional event, and the {@code @Scheduled} relay
 * re-drives anything still PENDING regardless, so nothing is lost even then.
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
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Let in-flight deliveries finish on shutdown instead of being killed mid-send, which would leave a
        // row PENDING with no error recorded — the ambiguous state slice 105 exists to eliminate.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
