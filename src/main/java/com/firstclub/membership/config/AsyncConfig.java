package com.firstclub.membership.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bounded executor for sending notifications off the request thread.
 *
 * <p>Bounded, because Spring's default {@code SimpleAsyncTaskExecutor} is unbounded and would spawn
 * threads without limit under a notification storm.
 *
 * <p>When saturated it drops tasks rather than running them on the caller ({@code CallerRunsPolicy}
 * would push notification backlog into API latency, which is exactly what this executor exists to
 * prevent). Dropping is safe here: the subscription is already committed and notification failures
 * are swallowed anyway. Each drop increments {@code membership.notification.dropped} so the
 * shedding is visible. Anything that must not be dropped belongs in a transactional outbox.
 */
@Configuration
@Slf4j
public class AsyncConfig {

  @Bean(name = "notificationExecutor")
  public ThreadPoolTaskExecutor notificationExecutor(MeterRegistry metrics) {
    Counter dropped =
        Counter.builder("membership.notification.dropped")
            .description("Notifications shed because the bounded executor was saturated")
            .register(metrics);

    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("notify-");
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(500);
    executor.setRejectedExecutionHandler(
        (task, pool) -> {
          dropped.increment();
          log.warn(
              "Notification queue saturated — shedding task. queued={} active={}",
              pool.getQueue().size(),
              pool.getActiveCount());
        });
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(20);
    executor.initialize();
    return executor;
  }
}
