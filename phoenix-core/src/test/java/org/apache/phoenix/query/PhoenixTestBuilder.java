/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.phoenix.query;

import org.apache.phoenix.thirdparty.com.google.common.base.Joiner;
import org.apache.phoenix.thirdparty.com.google.common.collect.Lists;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.SchemaUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Arrays.asList;
import static org.apache.phoenix.util.PhoenixRuntime.TENANT_ID_ATTRIB;

/**
 * PhoenixTestBuilder is a utility class using a Builder pattern.
 * Facilitates the following creation patterns
 * 1. Simple tables.
 * 2. Global Views on tables.
 * 3. Tenant Views on tables or global views.
 * 4. Indexes global or local on all of the above.
 * 5. Create multiple tenants
 * 6. Multiple views for a tenant.
 * Typical usage pattern when using this class is -
 * 1. Create schema for the test.
 * 2. Provide a DataSupplier for the above schema.
 * 3. Write validations for your tests.
 * PhoenixTestBuilder facilitates steps 1 and 2.
 */
public class PhoenixTestBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(PhoenixTestBuilder.class);
    private static final int MAX_SUFFIX_VALUE = 1000000;
    private static AtomicInteger NAME_SUFFIX = new AtomicInteger(0);

    private static String generateUniqueName() {
        int nextName = NAME_SUFFIX.incrementAndGet();
        if (nextName >= MAX_SUFFIX_VALUE) {
            throw new IllegalStateException("Used up all unique names");
        }
        return "T" + Integer.toString(MAX_SUFFIX_VALUE + nextName).substring(1);
    }

    /**
     * @return a formatted string with nullable info
     * for e.g "COL1 VARCHAR (NOT NULL), COL2 VARCHAR (NOT NULL), COL3 VARCHAR (NOT NULL)"
     */
    private static String getColumnsAsString(List<String> columns, List<String> types,
            boolean isPK) {
        assert (columns.size() == types.size());

        Joiner columnJoiner = Joiner.on(",");
        Joiner typeJoiner = Joiner.on(" ");
        List<String> columnDefinitions = Lists.newArrayList();
        for (int colIndex = 0; colIndex < columns.size(); colIndex++) {
            String column = columns.get(colIndex);
            String datatype = types.get(colIndex);
            if ((column != null) && (!column.isEmpty())) {
                String
                        columnWithType =
                        isPK ?
                                typeJoiner.join(column, datatype, "NOT NULL") :
                                typeJoiner.join(column, datatype);
                columnDefinitions.add(columnWithType);
            }
        }
        return columnJoiner.join(columnDefinitions);
    }

    /**
     * @return a formatted string with sort info
     * for e.g "PK_COL1, PK_COL2 , PK_COL3 DESC"
     */
    private static String getPKColumnsWithSort(List<String> pkColumns, List<String> sortTypes) {
        assert (sortTypes == null || sortTypes.size() == pkColumns.size());

        Joiner pkColumnJoiner = Joiner.on(",");
        Joiner sortTypeJoiner = Joiner.on(" ");
        List<String> pkColumnDefinitions = Lists.newArrayList();
        for (int colIndex = 0; colIndex < pkColumns.size(); colIndex++) {
            String column = pkColumns.get(colIndex);
            String sorttype = sortTypes == null ? null : sortTypes.get(colIndex);
            if ((column != null) && (!column.isEmpty())) {
                String
                        columnWithSortType =
                        sorttype == null || sorttype.isEmpty() ?
                                column :
                                sortTypeJoiner.join(column, sorttype);
                pkColumnDefinitions.add(columnWithSortType);
            }
        }
        return pkColumnJoiner.join(pkColumnDefinitions);
    }

    /**
     * @return a formatted string with CFs
     * for e.g  "A.COL1, B.COL2, C.COl3"
     */
    private static String getFQColumnsAsString(List<String> columns, List<String> families) {
        Joiner columnJoiner = Joiner.on(",");
        return columnJoiner.join(getFQColumnsAsList(columns, families));
    }

    private static List<String> getFQColumnsAsList(List<String> columns, List<String> families) {
        assert (columns.size() == families.size());

        Joiner familyJoiner = Joiner.on(".");
        List<String> columnDefinitions = Lists.newArrayList();
        int colIndex = 0;
        for (String family : families) {
            String column = columns.get(colIndex++);
            if ((column != null) && (!column.isEmpty())) {
                columnDefinitions.add(((family != null) && (!family.isEmpty())) ?
                        familyJoiner.join(family, column) :
                        column);
            }
        }
        return columnDefinitions;
    }

    /**
     * @return a formatted string with data types
     * for e.g =>  A.COL1 VARCHAR, A.COL2 VARCHAR, B.COL3 VARCHAR
     */
    private static String getFQColumnsAsString(List<String> columns, List<String> families,
            List<String> types) {
        Joiner columnJoiner = Joiner.on(",");
        return columnJoiner.join(getFQColumnsAsList(columns, families, types));
    }

    /*
     * -----------------
     * Helper methods
     * -----------------
     */

    private static List<String> getFQColumnsAsList(List<String> columns, List<String> families,
            List<String> types) {
        assert (columns.size() == families.size());

        Joiner familyJoiner = Joiner.on(".");
        Joiner typeJoiner = Joiner.on(" ");
        List<String> columnDefinitions = Lists.newArrayList();
        int colIndex = 0;
        for (String family : families) {
            String column = columns.get(colIndex);
            String datatype = types.get(colIndex);
            colIndex++;
            if ((column != null) && (!column.isEmpty())) {
                String columnWithType = typeJoiner.join(column, datatype);
                columnDefinitions.add(((family != null) && (!family.isEmpty())) ?
                        familyJoiner.join(family, columnWithType) :
                        columnWithType);
            }
        }
        return columnDefinitions;
    }

    // Test Data supplier interface for test writers to provide custom data.
    public interface DataSupplier {
        // return the values to be used for upserting data into the underlying entity.
        List<Object> getValues(int rowIndex);
    }

    // A Data Writer to be used in tests to upsert sample data (@see TestDataSupplier) into the sample schema.
    public interface DataWriter {
        // returns the columns that need to be upserted,
        // should match the #columns in TestDataSupplier::getValues().
        List<String> getUpsertColumns();

        void setUpsertColumns(List<String> upsertColumns);

        // returns the partial/overridden set of columns to be used for upserts.
        List<Integer> getColumnPositionsToUpdate();

        void setColumnPositionsToUpdate(List<Integer> columnPositionsToUpdate);

        // returns the connection to be used for upserting rows.
        Connection getConnection();

        void setConnection(Connection connection);

        // returns the target entity - whether to use the table, global-view or the tenant-view.
        String getTargetEntity();

        void setTargetEntity(String targetEntity);

        // return the data provider for this writer
        DataSupplier getTestDataSupplier();

        // template method to upsert rows using the above info.
        void upsertRow(int rowIndex) throws SQLException;

        void setDataSupplier(DataSupplier dataSupplier);
    }

    /**
     * Test SchemaBuilder defaults.
     */
    public static class DDLDefaults {
        public static final int MAX_ROWS = 10000;
        public static List<String> TABLE_PK_TYPES = asList("CHAR(15)", "CHAR(3)");
        public static List<String> GLOBAL_VIEW_PK_TYPES = asList("CHAR(15)");
        public static List<String> TENANT_VIEW_PK_TYPES = asList("CHAR(15)");

        public static List<String> COLUMN_TYPES = asList("VARCHAR", "VARCHAR", "VARCHAR");
        public static List<String> TABLE_COLUMNS = asList("COL1", "COL2", "COL3");
        public static List<String> GLOBAL_VIEW_COLUMNS = asList("COL4", "COL5", "COL6");
        public static List<String> TENANT_VIEW_COLUMNS = asList("COL7", "COL8", "COL9");

        public static List<String> TABLE_COLUMN_FAMILIES = asList(null, null, null);
        public static List<String> GLOBAL_VIEW_COLUMN_FAMILIES = asList(null, null, null);
        public static List<String> TENANT_VIEW_COLUMN_FAMILIES = asList(null, null, null);

        public static List<String> TABLE_PK_COLUMNS = asList("OID", "KP");
        public static List<String> GLOBAL_VIEW_PK_COLUMNS = asList("ID");
        public static List<String> TENANT_VIEW_PK_COLUMNS = asList("ZID");

        public static List<String> TABLE_INDEX_COLUMNS = asList("COL1");
        public static List<String> TABLE_INCLUDE_COLUMNS = asList("COL3");

        public static List<String> GLOBAL_VIEW_INDEX_COLUMNS = asList("COL4");
        public static List<String> GLOBAL_VIEW_INCLUDE_COLUMNS = asList("COL6");

        public static List<String> TENANT_VIEW_INDEX_COLUMNS = asList("COL9");
        public static List<String> TENANT_VIEW_INCLUDE_COLUMNS = asList("COL7");

        public static String
                DEFAULT_TABLE_PROPS =
                "COLUMN_ENCODED_BYTES=0, MULTI_TENANT=true,DEFAULT_COLUMN_FAMILY='Z'";
        public static String DEFAULT_TABLE_INDEX_PROPS = "";
        public static String DEFAULT_GLOBAL_VIEW_PROPS = "";
        public static String DEFAULT_GLOBAL_VIEW_INDEX_PROPS = "";
        public static String DEFAULT_TENANT_VIEW_PROPS = "";
        public static String DEFAULT_TENANT_VIEW_INDEX_PROPS = "";
        public static String DEFAULT_KP = "0EC";
        public static String DEFAULT_SCHEMA_NAME = "TEST_ENTITY";
        public static String DEFAULT_TENANT_ID_FMT = "00D0t%03d%s";

        public static String DEFAULT_CONNECT_URL = "jdbc:phoenix:localhost";

    }

    // Provides template method for upserting rows
    public static abstract class AbstractDataWriter implements DataWriter {

        public void upsertRow(int rowIndex) throws SQLException {
            List<String> upsertColumns = Lists.newArrayList();
            List<Object> upsertValues = Lists.newArrayList();

            if (getColumnPositionsToUpdate().isEmpty()) {
                upsertColumns.addAll(getUpsertColumns());
                upsertValues.addAll(getTestDataSupplier().getValues(rowIndex));
            } else {
                List<String> tmpColumns = getUpsertColumns();
                List<Object> tmpValues = getTestDataSupplier().getValues(rowIndex);
                for (int i : getColumnPositionsToUpdate()) {
                    upsertColumns.add(tmpColumns.get(i));
                    upsertValues.add(tmpValues.get(i));
                }
            }
            StringBuilder buf = new StringBuilder("UPSERT INTO ");
            buf.append(getTargetEntity());
            buf.append(" (").append(Joiner.on(",").join(upsertColumns)).append(") VALUES(");

            for (int i = 0; i < upsertValues.size(); i++) {
                buf.append("?,");
            }
            buf.setCharAt(buf.length() - 1, ')');

            LOGGER.info(buf.toString());

            Connection connection = getConnection();
            try (PreparedStatement stmt = connection.prepareStatement(buf.toString())) {
                for (int i = 0; i < upsertValues.size(); i++) {
                    stmt.setObject(i + 1, upsertValues.get(i));
                }
                stmt.execute();
                connection.commit();
            }
        }
    }

    // An implementation of the TestDataWriter.
    public static class BasicDataWriter extends AbstractDataWriter {
        List<String> upsertColumns = Lists.newArrayList();
        List<Integer> columnPositionsToUpdate = Lists.newArrayList();
        DataSupplier dataSupplier;
        Connection connection;
        String targetEntity;

        @Override public List<String> getUpsertColumns() {
            return upsertColumns;
        }

        @Override public void setUpsertColumns(List<String> upsertColumns) {
            this.upsertColumns = upsertColumns;
        }

        @Override public List<Integer> getColumnPositionsToUpdate() {
            return columnPositionsToUpdate;
        }

        @Override public void setColumnPositionsToUpdate(List<Integer> columnPositionsToUpdate) {
            this.columnPositionsToUpdate = columnPositionsToUpdate;
        }

        @Override public Connection getConnection() {
            return connection;
        }

        @Override public void setConnection(Connection connection) {
            this.connection = connection;
        }

        @Override public String getTargetEntity() {
            return targetEntity;
        }

        @Override public void setTargetEntity(String targetEntity) {
            this.targetEntity = targetEntity;
        }

        @Override public DataSupplier getTestDataSupplier() {
            return dataSupplier;
        }

        @Override public void setDataSupplier(DataSupplier dataSupplier) {
            this.dataSupplier = dataSupplier;
        }
    }

    /**
     * Schema builder for test writers to prepare various test scenarios.
     * It can be used to define the following type of schemas -
     * 1. Simple Table.
     * 2. Table with Global and Tenant Views.
     * 3. Table with Tenant Views
     * The above entities can be supplemented with indexes (global or local)
     * The builder also provides some reasonable defaults, but can be customized/overridden
     * for specific test requirements.
     */
    public static class SchemaBuilder {
        private static final AtomicInteger TENANT_COUNTER = new AtomicInteger(0);
        // variables holding the various options.
        boolean tableEnabled = false;
        boolean globalViewEnabled = false;
        boolean tenantViewEnabled = false;
        boolean tableIndexEnabled = false;
        boolean globalViewIndexEnabled = false;
        boolean tenantViewIndexEnabled = false;
        boolean tableCreated = false;
        boolean globalViewCreated = false;
        boolean tenantViewCreated = false;
        boolean tableIndexCreated = false;
        boolean globalViewIndexCreated = false;
        boolean tenantViewIndexCreated = false;
        String url;
        String entityKeyPrefix;
        String entityTableName;
        String entityGlobalViewName;
        String entityTenantViewName;
        PTable baseTable;
        ConnectOptions connectOptions;
        TableOptions tableOptions;
        GlobalViewOptions globalViewOptions;
        TenantViewOptions tenantViewOptions;
        TableIndexOptions tableIndexOptions;
        GlobalViewIndexOptions globalViewIndexOptions;
        TenantViewIndexOptions tenantViewIndexOptions;
        OtherOptions otherOptions;
        DataOptions dataOptions;

        public SchemaBuilder(String url) {
            this.url = url;
        }

        public PTable getBaseTable() {
            return baseTable;
        }

        void setBaseTable(PTable baseTable) {
            this.baseTable = baseTable;
        }

        public String getUrl() {
            return this.url;
        }

        public boolean isTableEnabled() {
            return tableEnabled;
        }

        public boolean isGlobalViewEnabled() {
            return globalViewEnabled;
        }

        public boolean isTenantViewEnabled() {
            return tenantViewEnabled;
        }

        public boolean isTableIndexEnabled() {
            return tableIndexEnabled;
        }

        public boolean isGlobalViewIndexEnabled() {
            return globalViewIndexEnabled;
        }

        public boolean isTenantViewIndexEnabled() {
            return tenantViewIndexEnabled;
        }

        /*
         *****************************
         * Setters and Getters
         *****************************
         */

        public boolean isTableCreated() {
            return tableCreated;
        }

        public boolean isGlobalViewCreated() {
            return globalViewCreated;
        }

        public boolean isTenantViewCreated() {
            return tenantViewCreated;
        }

        public boolean isTableIndexCreated() {
            return tableIndexCreated;
        }

        public boolean isGlobalViewIndexCreated() {
            return globalViewIndexCreated;
        }

        public boolean isTenantViewIndexCreated() {
            return tenantViewIndexCreated;
        }

        public String getEntityKeyPrefix() {
            return entityKeyPrefix;
        }

        public String getEntityTableName() {
            return entityTableName;
        }

        public String getEntityGlobalViewName() {
            return entityGlobalViewName;
        }

        public String getEntityTenantViewName() {
            return entityTenantViewName;
        }

        public ConnectOptions getConnectOptions() {
            return connectOptions;
        }

        public TableOptions getTableOptions() {
            return tableOptions;
        }

        public GlobalViewOptions getGlobalViewOptions() {
            return globalViewOptions;
        }

        public TenantViewOptions getTenantViewOptions() {
            return tenantViewOptions;
        }

        public TableIndexOptions getTableIndexOptions() {
            return tableIndexOptions;
        }

        public GlobalViewIndexOptions getGlobalViewIndexOptions() {
            return globalViewIndexOptions;
        }

        public TenantViewIndexOptions getTenantViewIndexOptions() {
            return tenantViewIndexOptions;
        }

        public OtherOptions getOtherOptions() {
            return otherOptions;
        }

        public DataOptions getDataOptions() {
            return dataOptions;
        }

        // "CREATE TABLE IF NOT EXISTS " +
		// tableName +
		// "(" +
		// 		dataColumns +
		// 		" CONSTRAINT pk PRIMARY KEY (" + pk + ")
		// 	)  " +
		// 	(dataProps.isEmpty() ? "" : dataProps;
        public SchemaBuilder withTableDefaults() {
            tableEnabled = true;
            tableCreated = false;
            tableOptions = TableOptions.withDefaults();
            return this;
        }

        // "CREATE TABLE IF NOT EXISTS " +
		// tableName +
		// "(" +
		// 		dataColumns + " CONSTRAINT pk PRIMARY KEY (" + pk + ")
		// 	)  " +
		// 	(dataProps.isEmpty() ? "" : dataProps;
        public SchemaBuilder withTableOptions(TableOptions options) {
            tableEnabled = true;
            tableCreated = false;
            tableOptions = options;
            return this;
        }

        // "CREATE VIEW IF NOT EXISTS " +
		// globalViewName +
		// AS SELECT * FROM " + tableName + " WHERE " + globalViewCondition;
        public SchemaBuilder withSimpleGlobalView() {
            globalViewEnabled = true;
            globalViewCreated = false;
            globalViewOptions = new GlobalViewOptions();
            return this;
        }

        // "CREATE VIEW IF NOT EXISTS " +
		// globalViewName +
		// "(" +
		// 		globalViewColumns + " CONSTRAINT pk PRIMARY KEY (" + globalViewPK + ")
		// 	) AS SELECT * FROM " + tableName + " WHERE " + globalViewCondition;
        public SchemaBuilder withGlobalViewDefaults() {
            globalViewEnabled = true;
            globalViewCreated = false;
            globalViewOptions = GlobalViewOptions.withDefaults();
            return this;
        }

        // "CREATE VIEW IF NOT EXISTS " +
		// globalViewName +
		// "(" +
		// 		globalViewColumns + " CONSTRAINT pk PRIMARY KEY (" + globalViewPK + ")
		// 	) AS SELECT * FROM " + tableName + " WHERE " + globalViewCondition;
        public SchemaBuilder withGlobalViewOptions(GlobalViewOptions options) {
            globalViewEnabled = true;
            globalViewCreated = false;
            globalViewOptions = options;
            return this;
        }

        // "CREATE VIEW IF NOT EXISTS " + tenantViewName + AS SELECT * FROM " + globalViewName;
        public SchemaBuilder withSimpleTenantView() {
            tenantViewEnabled = true;
            tenantViewCreated = false;
            tenantViewOptions = new TenantViewOptions();
            return this;
        }

        // "CREATE VIEW  IF NOT EXISTS " +
		// tenantViewName +
		// "(" +
		// 		tenantViewColumns + " CONSTRAINT pk PRIMARY KEY (" + tenantViewPK + ")
		// 	) AS SELECT * FROM " + globalViewName;
        public SchemaBuilder withTenantViewDefaults() {
            tenantViewEnabled = true;
            tenantViewCreated = false;
            tenantViewOptions = TenantViewOptions.withDefaults();
            return this;
        }

        // "CREATE VIEW  IF NOT EXISTS " +
		// tenantViewName +
		// "(" +
		// 		tenantViewColumns + " CONSTRAINT pk PRIMARY KEY (" + tenantViewPK + ")
		// 	) AS SELECT * FROM " + globalViewName;
        public SchemaBuilder withTenantViewOptions(TenantViewOptions options) {
            tenantViewEnabled = true;
            tenantViewCreated = false;
            tenantViewOptions = options;
            return this;
        }

        // "CREATE INDEX IF NOT EXISTS
		// "IDX_T_T000001"
		// ON "TEST_ENTITY"."T_T000001"(COL1) INCLUDE (COL3)"
        public SchemaBuilder withTableIndexDefaults() {
            tableIndexEnabled = true;
            tableIndexCreated = false;
            tableIndexOptions = TableIndexOptions.withDefaults();
            return this;
        }

        public SchemaBuilder withTableIndexOptions(TableIndexOptions options) {
            tableIndexEnabled = true;
            tableIndexCreated = false;
            tableIndexOptions = options;
            return this;
        }

        public SchemaBuilder withGlobalViewIndexDefaults() {
            globalViewIndexEnabled = true;
            globalViewIndexCreated = false;
            globalViewIndexOptions = GlobalViewIndexOptions.withDefaults();
            return this;
        }

        public SchemaBuilder withGlobalViewIndexOptions(GlobalViewIndexOptions options) {
            globalViewIndexEnabled = true;
            globalViewIndexCreated = false;
            globalViewIndexOptions = options;
            return this;
        }

        public SchemaBuilder withTenantViewIndexDefaults() {
            tenantViewIndexEnabled = true;
            tenantViewIndexCreated = false;
            tenantViewIndexOptions = TenantViewIndexOptions.withDefaults();
            return this;
        }

        public SchemaBuilder withTenantViewIndexOptions(TenantViewIndexOptions options) {
            tenantViewIndexEnabled = true;
            tenantViewIndexCreated = false;
            tenantViewIndexOptions = options;
            return this;
        }

        public SchemaBuilder withOtherDefaults() {
            this.otherOptions = OtherOptions.withDefaults();
            return this;
        }

        public SchemaBuilder withOtherOptions(OtherOptions otherOptions) {
            this.otherOptions = otherOptions;
            return this;
        }

        public SchemaBuilder withDataOptionsDefaults() {
            this.dataOptions = DataOptions.withDefaults();
            return this;
        }

        public SchemaBuilder withDataOptions(DataOptions dataOptions) {
            this.dataOptions = dataOptions;
            return this;
        }

        public SchemaBuilder withConnectOptions(ConnectOptions connectOptions) {
            this.connectOptions = connectOptions;
            return this;
        }

        public SchemaBuilder withConnectDefaults() {
            this.connectOptions = new ConnectOptions();
            return this;
        }

        // Build method for creating new tenants with existing table,
		// global and tenant view definitions.
        // If the tenant view definition is not changed then
		// the same view is created with different names for different tenants.
        public void buildWithNewTenant() throws Exception {
            tenantViewCreated = false;
            tenantViewIndexCreated = false;
            if (this.dataOptions == null) {
                this.dataOptions = DataOptions.withDefaults();
            }
            this.dataOptions.tenantId =
                    String.format(dataOptions.tenantIdFormat, TENANT_COUNTER.incrementAndGet(),
                            dataOptions.uniqueName);

            build();
        }

        // Build method for creating new tenant views with existing table,
		// global and tenant view definitions.
        // If the tenant view definition is not changed then
		// the same view is created with different names.
        public void buildNewView() throws Exception {
            tenantViewCreated = false;
            tenantViewIndexCreated = false;
            if (this.dataOptions == null) {
                this.dataOptions = DataOptions.withDefaults();
            }
            dataOptions.viewNumber = this.getDataOptions().getNextViewNumber();
            build();
        }

        // The main build method for the builder.
        public void build() throws Exception {

            // Set defaults if not specified
            if (this.otherOptions == null) {
                this.otherOptions = OtherOptions.withDefaults();
            }

            if (this.dataOptions == null) {
                this.dataOptions = DataOptions.withDefaults();
            }

            if (this.connectOptions == null) {
                this.connectOptions = new ConnectOptions();
            }

            if (connectOptions.useGlobalConnectionOnly
                    && connectOptions.useTenantConnectionForGlobalView) {
                throw new IllegalArgumentException(
                		"useTenantConnectionForGlobalView and useGlobalConnectionOnly both cannot be true");
            }

            String tableName = SchemaUtil.getEscapedArgument("T_" + dataOptions.uniqueName);
            String globalViewName = SchemaUtil.getEscapedArgument("V_" + dataOptions.uniqueName);
            String
                    tableSchemaName =
                    tableEnabled ? SchemaUtil.getEscapedArgument(tableOptions.schemaName) : "";
            String
                    globalViewSchemaName =
                    globalViewEnabled ?
                            SchemaUtil.getEscapedArgument(globalViewOptions.schemaName) :
                            "";
            String
                    tenantViewSchemaName =
                    tenantViewEnabled ?
                            SchemaUtil.getEscapedArgument(tenantViewOptions.schemaName) :
                            "";
            entityTableName = SchemaUtil.getTableName(tableSchemaName, tableName);
            entityGlobalViewName = SchemaUtil.getTableName(globalViewSchemaName, globalViewName);

            // Derive the keyPrefix to use.
            entityKeyPrefix =
                    connectOptions.useGlobalConnectionOnly ?
                            (String.format("Z%02d", dataOptions.getViewNumber())) :
                            (tenantViewEnabled && !globalViewEnabled ?
                                    (String.format("Z%02d", dataOptions.getViewNumber())) :
                                    DDLDefaults.DEFAULT_KP);

            String tenantViewName = SchemaUtil.getEscapedArgument(entityKeyPrefix);
            entityTenantViewName = SchemaUtil.getTableName(tenantViewSchemaName, tenantViewName);
            String globalViewCondition = String.format("KP = '%s'", entityKeyPrefix);

            // Table and Table Index creation.
            try (Connection globalConnection = getGlobalConnection()) {
                if (tableEnabled && !tableCreated) {
                    globalConnection.createStatement()
                            .execute(buildCreateTableStmt(entityTableName));
                    tableCreated = true;
                    PTableKey
                            tableKey =
                            new PTableKey(null, SchemaUtil.normalizeFullTableName(entityTableName));
                    setBaseTable(
                            globalConnection.unwrap(PhoenixConnection.class).getTable(tableKey));
                }
                // Index on Table
                if (tableIndexEnabled && !tableIndexCreated) {
                    String
                            indexOnTableName =
                            SchemaUtil.getEscapedArgument(String.format("IDX_%s",
                                    SchemaUtil.normalizeIdentifier(tableName)));
                    globalConnection.createStatement().execute(
                            buildCreateIndexStmt(indexOnTableName, entityTableName,
                                    tableIndexOptions.isLocal, tableIndexOptions.tableIndexColumns,
                                    tableIndexOptions.tableIncludeColumns,
                                    tableIndexOptions.indexProps));
                    tableIndexCreated = true;
                }
            }

            // Global View and View Index creation.
            try (Connection globalViewConnection = getGlobalViewConnection()) {
                if (globalViewEnabled && !globalViewCreated) {
                    globalViewConnection.createStatement().execute(
                            buildCreateGlobalViewStmt(entityGlobalViewName, entityTableName,
                                    globalViewCondition));
                    globalViewCreated = true;
                }
                // Index on GlobalView
                if (globalViewIndexEnabled && !globalViewIndexCreated) {
                    String
                            indexOnGlobalViewName =
                            String.format("IDX_%s", SchemaUtil.normalizeIdentifier(globalViewName));
                    globalViewConnection.createStatement().execute(
                            buildCreateIndexStmt(indexOnGlobalViewName, entityGlobalViewName,
                                    globalViewIndexOptions.isLocal,
                                    globalViewIndexOptions.globalViewIndexColumns,
                                    globalViewIndexOptions.globalViewIncludeColumns,
                                    globalViewIndexOptions.indexProps));
                    globalViewIndexCreated = true;
                }
            }

            // Tenant View and View Index creation.
            try (Connection tenantConnection = getTenantConnection()) {
                // Build tenant related views if any
                if (tenantViewEnabled && !tenantViewCreated) {
                    String tenantViewCondition;
                    if (globalViewEnabled) {
                        tenantViewCondition =
                                String.format("SELECT * FROM %s", entityGlobalViewName);
                    } else if (tableEnabled) {
                        tenantViewCondition =
                                String.format("SELECT * FROM %s WHERE KP = '%s'", entityTableName,
                                        entityKeyPrefix);
                    } else {
                        throw new IllegalStateException(
                                "Tenant View must be based on tables or global view");
                    }
                    tenantConnection.createStatement().execute(
                            buildCreateTenantViewStmt(entityTenantViewName, tenantViewCondition));
                    tenantViewCreated = true;
                }
                // Index on TenantView
                if (tenantViewIndexEnabled && !tenantViewIndexCreated) {
                    String indexOnTenantViewName = String.format("IDX_%s", entityKeyPrefix);
                    tenantConnection.createStatement().execute(
                            buildCreateIndexStmt(indexOnTenantViewName, entityTenantViewName,
                                    tenantViewIndexOptions.isLocal,
                                    tenantViewIndexOptions.tenantViewIndexColumns,
                                    tenantViewIndexOptions.tenantViewIncludeColumns,
                                    tenantViewIndexOptions.indexProps));
                    tenantViewIndexCreated = true;
                }
            }
        }

        // Helper method for CREATE INDEX stmt builder.
        private String buildCreateIndexStmt(String indexName, String onEntityName, boolean isLocal,
                List<String> indexColumns, List<String> includeColumns, String indexProps) {
            StringBuilder statement = new StringBuilder();
            statement.append(isLocal ?
                    "CREATE LOCAL INDEX IF NOT EXISTS " :
                    "CREATE INDEX IF NOT EXISTS ")
					.append(indexName)
					.append(" ON ")
                    .append(onEntityName)
					.append("(")
					.append(Joiner.on(",").join(indexColumns))
                    .append(") ")
					.append(includeColumns.isEmpty() ?
						"" :
						"INCLUDE (" + Joiner.on(",").join(includeColumns) + ") ")
                    .append((indexProps.isEmpty() ? "" : indexProps));

            LOGGER.info(statement.toString());
            return statement.toString();

        }

        // Helper method for CREATE TABLE stmt builder.
        private String buildCreateTableStmt(String fullTableName) {
            StringBuilder statement = new StringBuilder();
            StringBuilder tableDefinition = new StringBuilder();

            if (!tableOptions.tablePKColumns.isEmpty() || !tableOptions.tableColumns.isEmpty()) {
                tableDefinition.append(("("));
                if (!tableOptions.tablePKColumns.isEmpty()) {
                    tableDefinition.append(getColumnsAsString(tableOptions.tablePKColumns,
                            tableOptions.tablePKColumnTypes, true));
                }
                if (!tableOptions.tableColumns.isEmpty()) {
                    tableDefinition.append(tableOptions.tablePKColumns.isEmpty() ? "" : ",")
                            .append(getFQColumnsAsString(tableOptions.tableColumns,
                                    otherOptions.tableCFs, tableOptions.tableColumnTypes));
                }

                if (!tableOptions.tablePKColumns.isEmpty()) {
                    tableDefinition.append(" CONSTRAINT pk PRIMARY KEY ").append("(")
                            .append(getPKColumnsWithSort(tableOptions.tablePKColumns,
                                    tableOptions.tablePKColumnSort)).append(")");
                }
                tableDefinition.append((")"));
            }

            statement.append("CREATE TABLE IF NOT EXISTS ").append(fullTableName)
                    .append(tableDefinition.toString()).append(" ")
                    .append((tableOptions.tableProps.isEmpty() ? "" : tableOptions.tableProps));
            LOGGER.info(statement.toString());
            return statement.toString();
        }

        // Helper method for CREATE VIEW (GLOBAL) stmt builder.
        private String buildCreateGlobalViewStmt(String fullGlobalViewName, String fullTableName,
                String globalViewCondition) {
            StringBuilder statement = new StringBuilder();
            StringBuilder viewDefinition = new StringBuilder();

            if (!globalViewOptions.globalViewPKColumns.isEmpty()
                    || !globalViewOptions.globalViewColumns.isEmpty()) {
                viewDefinition.append(("("));
                if (!globalViewOptions.globalViewPKColumns.isEmpty()) {
                    viewDefinition.append(getColumnsAsString(globalViewOptions.globalViewPKColumns,
                            globalViewOptions.globalViewPKColumnTypes, true));
                }
                if (!globalViewOptions.globalViewColumns.isEmpty()) {
                    viewDefinition
                            .append(globalViewOptions.globalViewPKColumns.isEmpty() ? "" : ",")
                            .append(getFQColumnsAsString(globalViewOptions.globalViewColumns,
                                    otherOptions.globalViewCFs,
                                    globalViewOptions.globalViewColumnTypes));
                }

                if (!globalViewOptions.globalViewPKColumns.isEmpty()) {
                    viewDefinition.append(" CONSTRAINT pk PRIMARY KEY ").append("(")
                            .append(getPKColumnsWithSort(globalViewOptions.globalViewPKColumns,
                                    globalViewOptions.globalViewPKColumnSort)).append(")");
                }
                viewDefinition.append((")"));
            }

            statement.append("CREATE VIEW IF NOT EXISTS ").append(fullGlobalViewName)
                    .append(viewDefinition.toString()).append(" AS SELECT * FROM ")
                    .append(fullTableName).append(" WHERE ").append(globalViewCondition).append(" ")
                    .append((globalViewOptions.tableProps.isEmpty() ?
                            "" :
                            globalViewOptions.tableProps));
            LOGGER.info(statement.toString());
            return statement.toString();
        }

        // Helper method for CREATE VIEW (TENANT) stmt builder.
        private String buildCreateTenantViewStmt(String fullTenantViewName,
                String tenantViewCondition) {
            StringBuilder statement = new StringBuilder();
            StringBuilder viewDefinition = new StringBuilder();

            if (!tenantViewOptions.tenantViewPKColumns.isEmpty()
                    || !tenantViewOptions.tenantViewColumns.isEmpty()) {
                viewDefinition.append(("("));
                if (!tenantViewOptions.tenantViewPKColumns.isEmpty()) {
                    viewDefinition.append(getColumnsAsString(tenantViewOptions.tenantViewPKColumns,
                            tenantViewOptions.tenantViewPKColumnTypes, true));
                }
                if (!tenantViewOptions.tenantViewColumns.isEmpty()) {
                    viewDefinition
                            .append(tenantViewOptions.tenantViewPKColumns.isEmpty() ? "" : ",")
                            .append(getFQColumnsAsString(tenantViewOptions.tenantViewColumns,
                                    otherOptions.tenantViewCFs,
                                    tenantViewOptions.tenantViewColumnTypes));
                }

                if (!tenantViewOptions.tenantViewPKColumns.isEmpty()) {
                    viewDefinition.append(" CONSTRAINT pk PRIMARY KEY ").append("(")
                            .append(getPKColumnsWithSort(tenantViewOptions.tenantViewPKColumns,
                                    tenantViewOptions.tenantViewPKColumnSort)).append(")");
                }
                viewDefinition.append((")"));
            }

            statement.append("CREATE VIEW IF NOT EXISTS ").append(fullTenantViewName)
                    .append(viewDefinition.toString()).append(" AS ").append(tenantViewCondition)
                    .append(" ").append((tenantViewOptions.tableProps.isEmpty() ?
                    "" :
                    tenantViewOptions.tableProps));
            LOGGER.info(statement.toString());
            return statement.toString();
        }

        Connection getGlobalConnection() throws SQLException {
            return getPhoenixConnection(getUrl());
        }

        Connection getGlobalViewConnection() throws SQLException {
            return getPhoenixConnection(connectOptions.useTenantConnectionForGlobalView ?
                    getUrl() + ';' + TENANT_ID_ATTRIB + '=' + dataOptions.tenantId :
                    getUrl());
        }

        Connection getTenantConnection() throws SQLException {
            return getPhoenixConnection(connectOptions.useGlobalConnectionOnly ?
                    getUrl() :
                    getUrl() + ';' + TENANT_ID_ATTRIB + '=' + dataOptions.tenantId);
        }

        Connection getPhoenixConnection(String url) throws SQLException {
            return getPhoenixConnection(url, connectOptions.connectProps);
        }

        Connection getPhoenixConnection(String url, Properties props) throws SQLException {
            Connection phoenixConnection;
            if (props == null) {
                Properties connProps = PropertiesUtil.deepCopy(connectOptions.connectProps);
                phoenixConnection = DriverManager.getConnection(url, connProps);
            } else {
                phoenixConnection = DriverManager.getConnection(url, props);
            }
            phoenixConnection.setAutoCommit(true);
            return phoenixConnection;
        }

        /**
         * Option holders for various statement generation.
         */

        // Connect options.
        public static class ConnectOptions {
            Properties connectProps = new Properties();
            boolean useGlobalConnectionOnly = false;
            boolean useTenantConnectionForGlobalView = false;

            /*
             *****************************
             * Setters and Getters
             *****************************
             */

            public Properties getConnectProps() {
                return connectProps;
            }

            public void setConnectProps(Properties connectProps) {
                this.connectProps = connectProps;
            }

            public boolean isUseGlobalConnectionOnly() {
                return useGlobalConnectionOnly;
            }

            public void setUseGlobalConnectionOnly(boolean useGlobalConnectionOnly) {
                this.useGlobalConnectionOnly = useGlobalConnectionOnly;
            }

            public boolean isUseTenantConnectionForGlobalView() {
                return useTenantConnectionForGlobalView;
            }

            public void setUseTenantConnectionForGlobalView(
                    boolean useTenantConnectionForGlobalView) {
                this.useTenantConnectionForGlobalView = useTenantConnectionForGlobalView;
            }

        }

        // Table statement generation.
        public static class TableOptions {
            String schemaName = DDLDefaults.DEFAULT_SCHEMA_NAME;
            List<String> tableColumns = Lists.newArrayList();
            List<String> tableColumnTypes = Lists.newArrayList();
            List<String> tablePKColumns = Lists.newArrayList();
            List<String> tablePKColumnTypes = Lists.newArrayList();
            List<String> tablePKColumnSort;
            String tableProps = DDLDefaults.DEFAULT_TABLE_PROPS;

            /*
             *****************************
             * Setters and Getters
             *****************************
             */

            public static TableOptions withDefaults() {
                TableOptions options = new TableOptions();
                options.schemaName = DDLDefaults.DEFAULT_SCHEMA_NAME;
                options.tableColumns = Lists.newArrayList(DDLDefaults.TABLE_COLUMNS);
                options.tableColumnTypes = Lists.newArrayList(DDLDefaults.COLUMN_TYPES);
                options.tablePKColumns = Lists.newArrayList(DDLDefaults.TABLE_PK_COLUMNS);
                options.tablePKColumnTypes = Lists.newArrayList(DDLDefaults.TABLE_PK_TYPES);
                options.tableProps = DDLDefaults.DEFAULT_TABLE_PROPS;
                return options;
            }

            public String getSchemaName() {
                return schemaName;
            }

            public void setSchemaName(String schemaName) {
                this.schemaName = schemaName;
            }

            public List<String> getTableColumns() {
                return tableColumns;
            }

            public void setTableColumns(List<String> tableColumns) {
                this.tableColumns = tableColumns;
            }

            public List<String> getTableColumnTypes() {
                return tableColumnTypes;
            }

            public void setTableColumnTypes(List<String> tableColumnTypes) {
                this.tableColumnTypes = tableColumnTypes;
            }

            public List<String> getTablePKColumns() {
                return tablePKColumns;
            }

            public void setTablePKColumns(List<String> tablePKColumns) {
                this.tablePKColumns = tablePKColumns;
            }

            public List<String> getTablePKColumnTypes() {
                return tablePKColumnTypes;
            }

            public void setTablePKColumnTypes(List<String> tablePKColumnTypes) {
                this.tablePKColumnTypes = tablePKColumnTypes;
            }

            public List<String> getTablePKColumnSort() {
                return tablePKColumnSort;
            }

            public void setTablePKColumnSort(List<String> tablePKColumnSort) {
                this.tablePKColumnSort = tablePKColumnSort;
            }

            public String getTableProps() {
                return tableProps;
            }

            public void setTableProps(String tableProps) {
                this.tableProps = tableProps;
            }
        }

        // Global View statement generation.
        public static class GlobalViewOptions {
            String schemaName = DDLDefaults.DEFAULT_SCHEMA_NAME;
            List<String> globalViewColumns = Lists.newArrayList();
            List<String> globalViewColumnTypes = Lists.newArrayList();
            List<String> globalViewPKColumns = Lists.newArrayList();
            List<String> globalViewPKColumnTypes = Lists.newArrayList();
            List<String> globalViewPKColumnSort;
            String tableProps = DDLDefaults.DEFAULT_TENANT_VIEW_PROPS;
            String globalViewCondition;

            /*
             *****************************
             * Setters and Getters
             *****************************
             */

            public static GlobalViewOptions withDefaults() {
                GlobalViewOptions options = new GlobalViewOptions();
                options.schemaName = DDLDefaults.DEFAULT_SCHEMA_NAME;
                options.globalViewColumns = Lists.newArrayList(DDLDefaults.GLOBAL_VIEW_COLUMNS);
                options.globalViewColumnTypes = Lists.newArrayList(DDLDefaults.COLUMN_TYPES);
                options.globalViewPKColumns =
                        Lists.newArrayList(DDLDefaults.GLOBAL_VIEW_PK_COLUMNS);
                options.globalViewPKColumnTypes =
                        Lists.newArrayList(DDLDefaults.GLOBAL_VIEW_PK_TYPES);
                options.tableProps = DDLDefaults.DEFAULT_GLOBAL_VIEW_PROPS;
                options.globalViewCondition = "";
                return options;
            }

            public String getSchemaName() {
                return schemaName;
            }

            public void setSchemaName(String schemaName) {
                this.schemaName = schemaName;
            }

            public List<String> getGlobalViewColumns() {
                return globalViewColumns;
            }

            public void setGlobalViewColumns(List<String> globalViewColumns) {
                this.globalViewColumns = globalViewColumns;
            }

            public List<String> getGlobalViewColumnTypes() {
                return globalViewColumnTypes;
            }

            public void setGlobalViewColumnTypes(List<String> globalViewColumnTypes) {
                this.globalViewColumnTypes = globalViewColumnTypes;
            }

            public List<String> getGlobalViewPKColumns() {
                return globalViewPKColumns;
            }

            public void setGlobalViewPKColumns(List<String> globalViewPKColumns) {
                this.globalViewPKColumns = globalViewPKColumns;
            }

            public List<String> getGlobalViewPKColumnTypes() {
                return globalViewPKColumnTypes;
            }

            public void setGlobalViewPKColumnTypes(List<String> globalViewPKColumnTypes) {
                this.globalViewPKColumnTypes = globalViewPKColumnTypes;
            }

            public List<String> getGlobalViewPKColumnSort() {
                return globalViewPKColumnSort;
            }

            public void setGlobalViewPKColumnSort(List<String> globalViewPKColumnSort) {
                this.globalViewPKColumnSort = globalViewPKColumnSort;
            }

            public String getTableProps() {
                return tableProps;
            }

            public void setTableProps(String tableProps) {
                this.tableProps = tableProps;
            }

            public String getGlobalViewCondition() {
                return globalViewCondition;
            }

            public void setGlobalViewCondition(String globalViewCondition) {
                this.globalViewCondition = globalViewCondition;
            }
        }

        // Tenant View statement generation.
        public static class TenantViewOptions {
            String schemaName = DDLDefaults.DEFAULT_SCHEMA_NAME;
            List<String> tenantViewColumns = Lists.newArrayList();
            List<String> tenantViewColumnTypes = Lists.newArrayList();
            List<String> tenantViewPKColumns = Lists.newArrayList();
            List<String> tenantViewPKColumnTypes = Lists.newArrayList();
            List<String> tenantViewPKColumnSort;
            String tableProps = DDLDefaults.DEFAULT_TENANT_VIEW_PROPS;

            /*
             *****************************
             * Setters and Getters
             *****************************
             */

            public static TenantViewOptions withDefaults() {
                TenantViewOptions options = new TenantViewOptions();
                options.schemaName = DDLDefaults.DEFAULT_SCHEMA_NAME;
                options.tenantViewColumns = Lists.newArrayList(DDLDefaults.TENANT_VIEW_COLUMNS);
                options.tenantViewColumnTypes = Lists.newArrayList(DDLDefaults.COLUMN_TYPES);
                options.tenantViewPKColumns =
                        Lists.newArrayList(DDLDefaults.TENANT_VIEW_PK_COLUMNS);
                options.tenantViewPKColumnTypes =
                        Lists.newArrayList(DDLDefaults.TENANT_VIEW_PK_TYPES);
                options.tableProps = DDLDefaults.DEFAULT_TENANT_VIEW_PROPS;
                return options;
            }

            public String getSchemaName() {
                return schemaName;
            }

            public void setSchemaName(String schemaName) {
                this.schemaName = schemaName;
            }

            public List<String> getTenantViewColumns() {
                return tenantViewColumns;
            }

            public void setTenantViewColumns(List<String> tenantViewColumns) {
                this.tenantViewColumns = tenantViewColumns;
            }

            public List<String> getTenantViewColumnTypes() {
                return tenantViewColumnTypes;
            }

            public void setTenantViewColumnTypes(List<String> tenantViewColumnTypes) {
                this.tenantViewColumnTypes = tenantViewColumnTypes;
            }

            public List<String> getTenantViewPKColumns() {
                return tenantViewPKColumns;
            }

            public void setTenantViewPKColumns(List<String> tenantViewPKColumns) {
                this.tenantViewPKColumns = tenantViewPKColumns;
            }

            public List<String> getTenantViewPKColumnTypes() {
                return tenantViewPKColumnTypes;
            }

            public void setTenantViewPKColumnTypes(List<String> tenantViewPKColumnTypes) {
                this.tenantViewPKColumnTypes = tenantViewPKColumnTypes;
            }

            public List<String> getTenantViewPKColumnSort() {
                return tenantViewPKColumnSort;
            }

            public void setTenantViewPKColumnSort(List<String> tenantViewPKColumnSort) {
                this.tenantViewPKColumnSort = tenantViewPKColumnSort;
            }

            public String getTableProps() {
                return tableProps;
            }

            public void setTableProps(String tableProps) {
                this.tableProps = tableProps;
            }
        }

        // Table Index statement generation.
        public static class TableIndexOptions {
            List<String> tableIndexColumns = Lists.newArrayList();
            List<String> tableIncludeColumns = Lists.newArrayList();
            boolean isLocal = false;
            String indexProps = "";

            /*
             *****************************
             * Setters and Getters
             *****************************
             */

            public static TableIndexOptions withDefaults() {
                TableIndexOptions options = new TableIndexOptions();
                options.tableIndexColumns = Lists.newArrayList(DDLDefaults.TABLE_INDEX_COLUMNS);
                options.tableIncludeColumns = Lists.newArrayList(DDLDefaults.TABLE_INCLUDE_COLUMNS);
                options.indexProps = DDLDefaults.DEFAULT_TABLE_INDEX_PROPS;
                return options;
            }

            public List<String> getTableIndexColumns() {
                return tableIndexColumns;
            }

            public void setTableIndexColumns(List<String> tableIndexColumns) {
                this.tableIndexColumns = tableIndexColumns;
            }

            public List<String> getTableIncludeColumns() {
                return tableIncludeColumns;
            }

            public void setTableIncludeColumns(List<String> tableIncludeColumns) {
                this.tableIncludeColumns = tableIncludeColumns;
            }

            public boolean isLocal() {
                return isLocal;
            }

            public void setLocal(boolean local) {
                isLocal = local;
            }

            public String getIndexProps() {
                return indexProps;
            }

            public void setIndexProps(String indexProps) {
                this.indexProps = indexProps;
            }
        }

        // Global View Index statement generation.
        public static class GlobalViewIndexOptions {
            List<String> globalViewIndexColumns = Lists.newArrayList();
            List<String> globalViewIncludeColumns = Lists.newArrayList();
            boolean isLocal = false;
            String indexProps = "";

            /*
             *****************************
             * Setters and Getters
             *****************************
             */

            public static GlobalViewIndexOptions withDefaults() {
                GlobalViewIndexOptions options = new GlobalViewIndexOptions();
                options.globalViewIndexColumns =
                        Lists.newArrayList(DDLDefaults.GLOBAL_VIEW_INDEX_COLUMNS);
                options.globalViewIncludeColumns =
                        Lists.newArrayList(DDLDefaults.GLOBAL_VIEW_INCLUDE_COLUMNS);
                options.indexProps = DDLDefaults.DEFAULT_GLOBAL_VIEW_INDEX_PROPS;
                return options;
            }

            public List<String> getGlobalViewIndexColumns() {
                return globalViewIndexColumns;
            }

            public void setGlobalViewIndexColumns(List<String> globalViewIndexColumns) {
                this.globalViewIndexColumns = globalViewIndexColumns;
            }

            public List<String> getGlobalViewIncludeColumns() {
                return globalViewIncludeColumns;
            }

            public void setGlobalViewIncludeColumns(List<String> globalViewIncludeColumns) {
                this.globalViewIncludeColumns = globalViewIncludeColumns;
            }

            public boolean isLocal() {
                return isLocal;
            }

            public void setLocal(boolean local) {
                isLocal = local;
            }

            public String getIndexProps() {
                return indexProps;
            }

            public void setIndexProps(String indexProps) {
                this.indexProps = indexProps;
            }
        }

        // Tenant View Index statement generation.
        public static class TenantViewIndexOptions {
            List<String> tenantViewIndexColumns = Lists.newArrayList();
            List<String> tenantViewIncludeColumns = Lists.newArrayList();
            boolean isLocal = false;
            String indexProps = "";

            /*
             *****************************
             * Setters and Getters
             *****************************
             */

            public static TenantViewIndexOptions withDefaults() {
                TenantViewIndexOptions options = new TenantViewIndexOptions();
                options.tenantViewIndexColumns =
                        Lists.newArrayList(DDLDefaults.TENANT_VIEW_INDEX_COLUMNS);
                options.tenantViewIncludeColumns =
                        Lists.newArrayList(DDLDefaults.TENANT_VIEW_INCLUDE_COLUMNS);
                options.indexProps = DDLDefaults.DEFAULT_TENANT_VIEW_INDEX_PROPS;
                return options;
            }

            public List<String> getTenantViewIndexColumns() {
                return tenantViewIndexColumns;
            }

            public void setTenantViewIndexColumns(List<String> tenantViewIndexColumns) {
                this.tenantViewIndexColumns = tenantViewIndexColumns;
            }

            public List<String> getTenantViewIncludeColumns() {
                return tenantViewIncludeColumns;
            }

            public void setTenantViewIncludeColumns(List<String> tenantViewIncludeColumns) {
                this.tenantViewIncludeColumns = tenantViewIncludeColumns;
            }

            public boolean isLocal() {
                return isLocal;
            }

            public void setLocal(boolean local) {
                isLocal = local;
            }

            public String getIndexProps() {
                return indexProps;
            }

            public void setIndexProps(String indexProps) {
                this.indexProps = indexProps;
            }
        }

        public static class OtherOptions {
            String testName;
            List<String> tableCFs = Lists.newArrayList();
            List<String> globalViewCFs = Lists.newArrayList();
            List<String> tenantViewCFs = Lists.newArrayList();

            /*
             *****************************
             * Setters and Getters
             *****************************
             */

            public static OtherOptions withDefaults() {
                OtherOptions options = new OtherOptions();
                options.tableCFs = Lists.newArrayList(DDLDefaults.TABLE_COLUMN_FAMILIES);
                options.globalViewCFs = Lists.newArrayList(DDLDefaults.GLOBAL_VIEW_COLUMN_FAMILIES);
                options.tenantViewCFs = Lists.newArrayList(DDLDefaults.TENANT_VIEW_COLUMN_FAMILIES);
                return options;
            }

            public String getTestName() {
                return testName;
            }

            public void setTestName(String testName) {
                this.testName = testName;
            }

            public List<String> getTableCFs() {
                return tableCFs;
            }

            public void setTableCFs(List<String> tableCFs) {
                this.tableCFs = tableCFs;
            }

            public List<String> getGlobalViewCFs() {
                return globalViewCFs;
            }

            public void setGlobalViewCFs(List<String> globalViewCFs) {
                this.globalViewCFs = globalViewCFs;
            }

            public List<String> getTenantViewCFs() {
                return tenantViewCFs;
            }

            public void setTenantViewCFs(List<String> tenantViewCFs) {
                this.tenantViewCFs = tenantViewCFs;
            }
        }

        public static class DataOptions {
            String uniqueName = "";
            String tenantIdFormat = DDLDefaults.DEFAULT_TENANT_ID_FMT;
            int viewNumber = 0;
            AtomicInteger viewCounter = new AtomicInteger(0);
            String tenantId = "";

            /*
             *****************************
             * Setters and Getters
             *****************************
             */

            public static DataOptions withDefaults() {
                DataOptions options = new DataOptions();
                options.uniqueName = generateUniqueName();
                options.viewCounter = new AtomicInteger(0);
                options.tenantId =
                        String.format(options.tenantIdFormat, TENANT_COUNTER.get(),
                                options.uniqueName);
                return options;
            }

            public int getNextViewNumber() {
                return viewNumber = viewCounter.incrementAndGet();
            }

            public int getViewNumber() {
                return viewNumber;
            }

            public String getTenantIdFormat() {
                return tenantIdFormat;
            }

            public void setTenantIdFormat(String tenantIdFormat) {
                this.tenantIdFormat = tenantIdFormat;
            }

            public String getUniqueName() {
                return uniqueName;
            }

            public void setUniqueName(String uniqueName) {
                this.uniqueName = uniqueName;
            }

            public String getTenantId() {
                return tenantId;
            }

            public void setTenantId(String tenantId) {
                this.tenantId = tenantId;
            }
        }
    }

}
