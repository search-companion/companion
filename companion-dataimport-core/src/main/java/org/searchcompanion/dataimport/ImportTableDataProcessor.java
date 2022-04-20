package org.searchcompanion.dataimport;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ImportTableDataProcessor {

    private int currentTableIndex = 0;
    private final List<String> tableNames;
    private final Map<String, List<Map<String, Object>>> tablesMap = new ConcurrentHashMap<>();

    private ImportTableDataProcessor() {
        this.tableNames = null;
    }

    public ImportTableDataProcessor(List<String> tableNames) {
        this.tableNames = Collections.unmodifiableList(new ArrayList<>(tableNames));
    }

    public int getCurrentTableIndex() {
        return currentTableIndex;
    }

    public String getCurrentTableName() {
        return tableNames.get(currentTableIndex);
    }

    public List<String> getTableNames() {
        return tableNames;
    }

    public int incrementTableIndex() {
        return this.currentTableIndex++;
    }

    public Map<String, List<Map<String, Object>>> getTablesMap() {
        // generate linkedHashMap from concurrentHashMap to ensure order of tables is respected
        Map<String, List<Map<String, Object>>> map = new LinkedHashMap<>();
        for (String tableName : getTableNames()) {
            map.put(tableName, tablesMap.get(tableName));
        }
        return Collections.unmodifiableMap(map);
    }

    public void appendToTablesMap(String tableName, List<Map<String, Object>> records) {
        tablesMap.put(tableName, records);
    }

    public boolean hasNextTable() {
        return currentTableIndex < tableNames.size();
    }

    public String toString() {
        StringBuilder tableNamesStringBuilder = new StringBuilder().append("{");
        boolean first = true;
        for (String tableName : tableNames) {
            if (!first) {
                tableNamesStringBuilder.append(", ");
            } else {
                first = false;
            }
            tableNamesStringBuilder.append(
                    String.format(
                            "%s(%s)",
                            tableName,
                            tablesMap.get(tableName) == null ?
                                "null" : tablesMap.get(tableName).size())
            );
        }
        tableNamesStringBuilder.append("}");
        return String.format(
                "%s[currentTableIndex=%d, tableNames=%s]",
                getClass().getCanonicalName(),
                currentTableIndex,
                tableNamesStringBuilder.toString()
        );
    }

}
