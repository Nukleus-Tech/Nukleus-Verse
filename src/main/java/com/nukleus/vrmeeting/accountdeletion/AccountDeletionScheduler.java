package com.nukleus.vrmeeting.accountdeletion;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

/** Private scheduler: does not enable or alter scheduling in any existing component. */
@Component
public class AccountDeletionScheduler implements SmartLifecycle {
    private final AccountDeletionProperties properties;
    private final AccountDeletionProcessor processor;
    private ScheduledExecutorService executor;
    public AccountDeletionScheduler(AccountDeletionProperties properties, AccountDeletionProcessor processor) {
        this.properties = properties; this.processor = processor;
    }
    @Override public boolean isAutoStartup() {
        return properties.isSchedulerEnabled() && properties.isDestructiveEnabled();
    }
    @Override public synchronized void start() {
        if (!isAutoStartup() || isRunning()) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "account-deletion-worker"); t.setDaemon(true); return t;
        });
        executor.scheduleWithFixedDelay(processor::runBatch, 60, 60, TimeUnit.SECONDS);
    }
    @Override public synchronized void stop() {
        if (executor != null) { executor.shutdown(); executor = null; }
    }
    @Override public synchronized boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }
}
