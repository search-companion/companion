package org.searchcompanion.dataimport;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;

import java.util.List;
import java.util.Map;

public interface SolrMapper {

    String DELETEFLAG_TABLE_PLACEHOLDER = "DELETEFLAG_TABLE_PLACEHOLDER";
    String DELETEFLAG_FIELD_PLACEHOLDER = "DELETEFLAG_FIELD_PLACEHOLDER";

    SolrMapperResult mapTablesDataToSolrMapperResult(Exchange exchange, Map<String, List<Map<String, Object>>> tablesData, Map<String, Map<String, String>> tablesFieldsMap);

}
