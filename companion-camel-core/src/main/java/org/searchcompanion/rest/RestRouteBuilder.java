package org.searchcompanion.rest;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

public class RestRouteBuilder extends RouteBuilder {

    public void configure() {

        onException(Exception.class)
                .handled(true)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                .setBody().simple("Invalid request (${exception.message})");

        rest("/c")
                .id("rest-collections-endpoint")
                .description("Companion collections rest endpoint")
                .produces("application/json")
                .get("{collection}")
                .security("basicAuth")
                .to("direct-vm:rest-task");

    }

}
