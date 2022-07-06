package org.searchcompanion.core;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class TaskHandler {

    private final CamelContext camelContext;
    private final String defaulttaskExclusiveLockKey;
    private final ConcurrentHashMap<String, Semaphore> semaphoreMap = new ConcurrentHashMap<>();

    private TaskHandler(CamelContext camelContext) {
        this.camelContext = camelContext;
        this.defaulttaskExclusiveLockKey = camelContext.toString().concat("-").concat(this.toString());
    }

    public static TaskHandler initWithContext(CamelContext camelContext) {
        return new TaskHandler(camelContext);
    }

    public void allocate(Exchange exchange) throws IllegalStateException, InterruptedException {
        if (!exchange.getProperty("taskExclusiveLock", Boolean.FALSE, Boolean.class)) {
            return;
        }
        if (exchange.getProperty("taskExclusiveLockKey") == null) {
            exchange.setProperty("taskExclusiveLockKey", defaulttaskExclusiveLockKey);
        }
        String taskExclusiveLockKey = exchange.getProperty("taskExclusiveLockKey", String.class);
        if (!semaphoreMap.containsKey(taskExclusiveLockKey)) {
            semaphoreMap.put(taskExclusiveLockKey, new Semaphore(1));
        }
        if (!semaphoreMap.get(taskExclusiveLockKey).tryAcquire(0, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Only one long running task can be run at the same time for key = '" + taskExclusiveLockKey + "' !");
        }
        exchange.setProperty("hasExclusiveLockAcquired", true);
    }

    public void deallocate(Exchange exchange) {
        String taskExclusiveLockKey = exchange.getProperty("taskExclusiveLockKey", String.class);
        if (exchange.getProperty("hasExclusiveLockAcquired", Boolean.FALSE, Boolean.class)) {
            semaphoreMap.get(taskExclusiveLockKey).release();
        }
        exchange.removeProperty("hasExclusiveLockAcquired");
    }

    public void delayThread(Exchange exchange) throws InterruptedException {
        long sleepTimeMillis = exchange.getProperty("DelayTimeOut", 20L, Long.class);
        Thread.sleep(sleepTimeMillis);
    }

}
