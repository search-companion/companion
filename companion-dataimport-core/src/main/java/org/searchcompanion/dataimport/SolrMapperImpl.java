package org.searchcompanion.dataimport;

import org.apache.camel.Exchange;
import org.apache.camel.component.solr.SolrConstants;
import org.apache.solr.common.SolrInputDocument;

import java.util.List;
import java.util.Map;

public class SolrMapperImpl extends SolrMapperBase {

    public SolrMapperResult mapTablesDataToSolrMapperResult(Exchange exchange, Map<String, List<Map<String, Object>>> tablesData, Map<String, Map<String, String>> tablesFieldsMap) {
        String documentId = exchange.getMessage().getHeader("ProcessId", String.class);
        ImportTableDataProcessor importTableDataProcessor = exchange.getMessage().getHeader("importTableDataProcessor", ImportTableDataProcessor.class);
        String masterTableName = importTableDataProcessor.getTableNames().get(0);
        if (isDeleteFlagSet(masterTableName, tablesData, tablesFieldsMap)) {
            return new SolrMapperResult(
                    SolrConstants.OPERATION_DELETE_BY_QUERY,
                    documentId
            );
        }
        return new SolrMapperResult(
                SolrConstants.OPERATION_INSERT_STREAMING,
                mapTablesDataToSolrDocument(documentId, importTableDataProcessor.getTableNames(), tablesData, tablesFieldsMap)
        );
    }

    public SolrInputDocument mapTablesDataToSolrDocument(
                                        String documentId,
                                        List<String> orderedTableNames,
                                        Map<String, List<Map<String, Object>>> tablesData,
                                        Map<String, Map<String, String>> tablesFieldsMap
    ) {
        SolrInputDocument solrInputDocument = new SolrInputDocument();
        for (String tableName : orderedTableNames) {
            List<Map<String, Object>> dataMapList = tablesData.get(tableName);
            if (dataMapList != null && !dataMapList.isEmpty()) {
                for (Map<String, Object> dataMap : dataMapList) {
                    for (Map.Entry<String, String> entry : tablesFieldsMap.get(tableName).entrySet()) {
                        addField(solrInputDocument, entry.getKey(), dataMap.get(entry.getValue()));
                    }
                }
            }
        }
        if (solrInputDocument.isEmpty()) {
            throw new IllegalStateException(
                    String.format("No fields were mapped to SolrDocument for document '%s'", documentId)
            );
        }
        return solrInputDocument;
    }

    public boolean isDeleteFlagSet(String masterTableName, Map<String, List<Map<String, Object>>> tablesData, Map<String, Map<String, String>> tablesFieldsMap) {
        String fieldNameWithDeleteFlag =
                tablesFieldsMap.get(DELETEFLAG_TABLE_PLACEHOLDER) != null ?
                        tablesFieldsMap.get(DELETEFLAG_TABLE_PLACEHOLDER).get(DELETEFLAG_FIELD_PLACEHOLDER) :
                        null;
        if (fieldNameWithDeleteFlag != null
                && !fieldNameWithDeleteFlag.isEmpty()) {
            List<Map<String, Object>> masterTableRecords = tablesData.get(masterTableName);
            if (!masterTableRecords.isEmpty()) {
                if (!masterTableRecords.get(0).containsKey(fieldNameWithDeleteFlag)) {
                    throw new IllegalStateException(
                        String.format(
                                "Value for delete flag not available in column '%s' of master table '%s'. " +
                                        "Check the configuration of the master table and the mapper.field.deleteflag.",
                                fieldNameWithDeleteFlag,
                                masterTableName
                        )
                    );
                }
                if (isValueForDelete(masterTableRecords.get(0).get(fieldNameWithDeleteFlag))) {
                    return true;
                }
            }
        }
        return false;
    }

}
