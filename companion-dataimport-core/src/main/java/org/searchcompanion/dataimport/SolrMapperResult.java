package org.searchcompanion.dataimport;

public class SolrMapperResult {

    private final String solrOperation;
    private final Object object;

    public SolrMapperResult(String solrOperation, Object object) {
        this.solrOperation = solrOperation;
        this.object = object;
    }

    public String getSolrOperation() {
        return solrOperation;
    }

    public Object getObject() {
        return object;
    }
}
