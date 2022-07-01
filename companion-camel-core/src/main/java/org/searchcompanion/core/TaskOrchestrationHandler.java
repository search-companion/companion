package org.searchcompanion.core;

import org.apache.camel.Exchange;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class TaskOrchestrationHandler {

    private final Semaphore binarySemaphore = new Semaphore(1);

    public void allocate(Exchange exchange) throws IllegalStateException, InterruptedException {
        if (!binarySemaphore.tryAcquire(0, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Only one long running task can be run at the same time!");
        }
        exchange.setProperty("hasAllocationAcquired", true);
    }

    public void deallocate(Exchange exchange) {
        if (exchange.getProperty("hasAllocationAcquired", false, Boolean.class)) {
            binarySemaphore.release();
        }
    }

    public void delay(Exchange exchange) throws InterruptedException {
        long sleepTimeMillis = exchange.getProperty("DelayTimeOut", 20L, Long.class);
        Thread.sleep(sleepTimeMillis);
    }

}
