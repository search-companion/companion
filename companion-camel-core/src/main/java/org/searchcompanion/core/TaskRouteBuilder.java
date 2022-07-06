package org.searchcompanion.core;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;

public class TaskRouteBuilder extends RouteBuilder {

    @Override
    public void configure() {
        TaskHandler taskHandler = TaskHandler.initWithContext(getContext());

        from("direct:task-init").routeId("task-init")
                .onException(java.lang.IllegalStateException.class).handled(true)
                    .log(LoggingLevel.INFO, "==== Task ${exchangeProperty.taskId}: IllegalStateException: ${exception}")
                    .to("direct:task-reject")
                .end()
                .filter(simple("${exchangeProperty.taskId} == null"))
                    .setProperty("taskId", simple("TASK-${exchangeId}"))
                .end()
                .log(LoggingLevel.INFO, "==== Task ${exchangeProperty.taskId}: Task received ====")
                .choice()
                    .when(simple("${exchangeProperty.isSingleThreadProcess}"))
                        .bean(taskHandler, "allocate")
                        .to("seda:process-task")
                    .otherwise()
                        .to("seda:process-task")
                .end()
                .log(LoggingLevel.INFO, "==== Task ${exchangeProperty.taskId}: Task dispatched ====");

        from("direct:task-reject").routeId("task-reject")
                .log(LoggingLevel.WARN, "==== Task ${exchangeProperty.taskId}: Task REJECTED ====");

        from("seda:process-task?pollTimeout=10&concurrentConsumers=10").routeId("process-task")
                .to("direct:task-process")
                .log(LoggingLevel.INFO, "==== Task ${exchangeProperty.taskId}: Task finished ====")
                .bean(taskHandler, "deallocate");

        from("direct:task-process").routeId("task-process")
                .choice()
                    .when(simple("${exchangeProperty.taskAction} == 'delay'"))
                        .log(LoggingLevel.INFO, "==== Task ${exchangeProperty.taskId}: Task 'delay' started ====")
                        .bean(taskHandler, "delayThread")
                    .otherwise()
                        .log(LoggingLevel.ERROR, "==== Task ${exchangeProperty.taskId}: Action '${exchangeProperty.taskAction}' not valid ====");

    }

}
