package org.searchcompanion.core;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;

public class TaskDispatcherRouteBuilder extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        from("timer:t0-init-app?delay=10&repeatCount=1").routeId("t0-init-app").startupOrder(1)
                .setProperty("TaskId", constant("COMPANION-INIT"))
                .log(LoggingLevel.INFO, "===== Task ${exchangeProperty.TaskId} (${exchangeId} [${exchange.pattern}]): initialization done ====");

        from("direct:t1-dispatch-task").routeId("t1-dispatch-task")
                .onException(java.lang.IllegalStateException.class).handled(true)
                    .log(LoggingLevel.WARN, "==== Task ${exchangeProperty.TaskId} (${exchangeId} [${exchange.pattern}]): Task REJECTED ====")
                .end()
                .log(LoggingLevel.INFO, "==== Task ${exchangeProperty.TaskId} (${exchangeId} [${exchange.pattern}]): Task received ====")
                .choice()
                    .when(simple("${exchangeProperty.isSingleThreadProcess}"))
                        .setHeader("shouldExecuteTask", constant(true))
                        .to("seda:t2-single-task")
                        .setHeader("shouldExecuteTask", constant(false))
                        .to("seda:t2-single-task")
                    .otherwise()
                        .to("seda:t2-parallel-task")
                .log(LoggingLevel.INFO, "==== Task ${exchangeProperty.TaskId} (${exchangeId} [${exchange.pattern}]): Task dispatched ====");

        from("seda:t2-single-task?size=1&pollTimeout=10").routeId("t2-single-task")
                .filter(simple("${header.shouldExecuteTask}"))
                .to("direct:t3-process-task");
        
        from("seda:t2-parallel-task?pollTimeout=10&concurrentConsumers=10").routeId("t2-parallel-task")
                .to("direct:t3-process-task");

        from("direct:t3-process-task").routeId("t3-process-task")
                .bean("handler", "delay")
                .log(LoggingLevel.INFO, "==== Task ${exchangeProperty.TaskId} (${exchangeId} [${exchange.pattern}]): Task finished ====");

    }
}
