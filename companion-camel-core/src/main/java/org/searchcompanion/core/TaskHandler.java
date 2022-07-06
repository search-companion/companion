package org.searchcompanion.core;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class TaskHandler {


    private final CamelContext camelContext;
    private final String defaultTaskLockKey = this.toString();
    private final ConcurrentHashMap<String, Semaphore> semaphoreMap = new ConcurrentHashMap();

    private TaskHandler(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    public static TaskHandler initWithContext(CamelContext camelContext) {
        return new TaskHandler(camelContext);
    }

    public void allocate(Exchange exchange) throws IllegalStateException, InterruptedException {
        if (exchange.getProperty("taskLockKey") == null) {
            exchange.setProperty("taskLockKey", defaultTaskLockKey);
        }
        String taskLockKey = exchange.getProperty("taskLockKey", String.class);
        if (!semaphoreMap.containsKey(taskLockKey)) {
            semaphoreMap.put(taskLockKey, new Semaphore(1));
        }
        if (!semaphoreMap.get(taskLockKey).tryAcquire(0, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Only one long running task can be run at the same time for key = '" + taskLockKey + "' !");
        }
        exchange.setProperty("hasAllocationAcquired", true);
    }

    public void deallocate(Exchange exchange) {
        String taskLockKey = exchange.getProperty("taskLockKey", String.class);
        if (exchange.getProperty("hasAllocationAcquired", false, Boolean.class)) {
            semaphoreMap.get(taskLockKey).release();
        }
    }

    public void delayThread(Exchange exchange) throws InterruptedException {
        long sleepTimeMillis = exchange.getProperty("DelayTimeOut", 20L, Long.class);
        Thread.sleep(sleepTimeMillis);
    }

}
