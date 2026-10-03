package com.myplus.education.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * EDU-NOTIFY-2 — a full delivery queue must never put SMTP on the request thread.
 *
 * <p>With {@code CallerRunsPolicy} a rejected delivery ran on whoever submitted it — the servlet thread that
 * published the notice — and the publish came back InternalError past the gateway's 20 s limit. The row is
 * already PENDING by then and the scheduled relay re-drives it, so the right answer is to shed the task.
 */
class NotifyAsyncConfigTest {

    @Test
    @DisplayName("a delivery rejected by a full pool does NOT run on the calling thread, and does not throw")
    void rejectedDeliveryIsNotRunByTheCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), new NotifyAsyncConfig.LeavePendingForRelay());
        try {
            Runnable blocker = () -> { try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } };
            pool.execute(blocker);   // occupies the only thread
            pool.execute(blocker);   // fills the only queue slot

            AtomicReference<Thread> ranOn = new AtomicReference<>();
            assertThatCode(() -> pool.execute(() -> ranOn.set(Thread.currentThread())))
                    .as("a full queue must not surface as an error to the publisher").doesNotThrowAnyException();

            assertThat(ranOn.get()).as("CallerRunsPolicy would have run it right here, on the request thread").isNull();
        } finally {
            release.countDown();
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("the real notifyExecutor bean sheds — it is not CallerRunsPolicy")
    void theBeanUsesTheSheddingPolicy() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new NotifyAsyncConfig().notifyExecutor();
        try {
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(NotifyAsyncConfig.LeavePendingForRelay.class);
        } finally {
            executor.shutdown();
        }
    }
}
