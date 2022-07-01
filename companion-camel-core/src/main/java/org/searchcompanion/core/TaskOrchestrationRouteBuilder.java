package org.searchcompanion.core;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;

public class TaskOrchestrationRouteBuilder extends RouteBuilder {

    @Override
    public void configure() {

        TaskOrchestrationHandler taskOrchestrationHandler = new TaskOrchestrationHandler();

        from("timer:initialize?delay=1&repeatCount=1").routeId("initialize").startupOrder(0)
                .setProperty("TaskId", constant("COMPANION-INIT"))
                .log(LoggingLevel.INFO, "===== Task ${exchangeProperty.TaskId} (${exchangeId} [${exchange.pattern}]): initialization done ====");

        from("direct:dispatch-task").routeId("dispatch-task")
                .onException(java.lang.IllegalStateException.class).handled(true)
                    .to("direct:dispatch-task-rejected")
                .end()
                .log(LoggingLevel.INFO, "==== Task ${exchangeProperty.TaskId} (${exchangeId} [${exchange.pattern}]): Task received ====")
                .choice()
                    .when(simple("${exchangeProperty.isSingleThreadProcess}"))
                        .bean(taskOrchestrationHandler, "allocate")
                        .to("seda:process-task")
                    .otherwise()
                        .to("seda:process-task")
                .end()
                .log(LoggingLevel.INFO, "==== Task ${exchangeProperty.TaskId} (${exchangeId} [${exchange.pattern}]): Task dispatched ====");

        from("direct:dispatch-task-rejected").routeId("dispatch-task-rejected")
                .log(LoggingLevel.WARN, "==== Task ${exchangeProperty.TaskId} (${exchangeId} [${exchange.pattern}]): Task REJECTED ====");

        from("seda:process-task?pollTimeout=10&concurrentConsumers=10").routeId("process-task")
                .bean(taskOrchestrationHandler, "delay")
                .log(LoggingLevel.INFO, "==== Task ${exchangeProperty.TaskId} (${exchangeId} [${exchange.pattern}]): Task finished ====")
                .bean(taskOrchestrationHandler, "deallocate");

    }
}
