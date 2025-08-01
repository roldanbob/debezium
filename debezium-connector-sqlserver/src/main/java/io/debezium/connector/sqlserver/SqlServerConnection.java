/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.sqlserver;

import java.io.Reader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.sqlserver.jdbc.SQLServerDriver;

import io.debezium.DebeziumException;
import io.debezium.annotation.VisibleForTesting;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.config.Field;
import io.debezium.data.Envelope;
import io.debezium.jdbc.JdbcConfiguration;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.spi.Partition;
import io.debezium.relational.Column;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;

/**
 * {@link JdbcConnection} extension to be used with Microsoft SQL Server
 *
 * @author Horia Chiorean (hchiorea@redhat.com), Jiri Pechanec
 *
 */
public class SqlServerConnection extends JdbcConnection {

    public static final String INSTANCE_NAME = "instance";

    private static final String GET_DATABASE_NAME = "SELECT name FROM sys.databases WHERE name = ?";

    private static final Logger LOGGER = LoggerFactory.getLogger(SqlServerConnection.class);

    private static final String STATEMENTS_PLACEHOLDER = "#";
    private static final String DATABASE_NAME_PLACEHOLDER = "#db";
    private static final String TABLE_NAME_PLACEHOLDER = "#table";
    private static final String FUNCTION_NAME_PLACEHOLDER = "#function";
    private static final String GET_ALL_CHANGES_FUNCTION_PREFIX = "fn_cdc_get_all_changes_";
    private static final String GET_MAX_LSN = "SELECT #db.sys.fn_cdc_get_max_lsn()";
    private static final String GET_MAX_TRANSACTION_LSN = "SELECT MAX(start_lsn) FROM #db.cdc.lsn_time_mapping WHERE tran_id <> 0x00";
    private static final String GET_NTH_TRANSACTION_LSN_FROM_BEGINNING = "SELECT MAX(start_lsn) FROM (SELECT TOP (?) start_lsn FROM #db.cdc.lsn_time_mapping WHERE tran_id <> 0x00 ORDER BY start_lsn) as next_lsns";
    private static final String GET_NTH_TRANSACTION_LSN_FROM_LAST = "SELECT MAX(start_lsn) FROM (SELECT TOP (? + 1) start_lsn FROM #db.cdc.lsn_time_mapping WHERE start_lsn >= ? AND tran_id <> 0x00 ORDER BY start_lsn) as next_lsns";

    private static final String GET_MIN_LSN = "SELECT #db.sys.fn_cdc_get_min_lsn(?)";
    private static final String LOCK_TABLE = "SELECT * FROM #table WITH (TABLOCKX)";
    private static final String INCREMENT_LSN = "SELECT #db.sys.fn_cdc_increment_lsn(?)";
    protected static final String LSN_TIMESTAMP_SELECT_STATEMENT = "TODATETIMEOFFSET(#db.sys.fn_cdc_map_lsn_to_time([__$start_lsn]), DATEPART(TZOFFSET, SYSDATETIMEOFFSET()))";
    private static final String GET_ALL_CHANGES_FOR_TABLE_SELECT = "SELECT [__$start_lsn], [__$seqval], [__$operation], [__$update_mask], #, "
            + LSN_TIMESTAMP_SELECT_STATEMENT;
    private static final String GET_ALL_CHANGES_FOR_TABLE_FROM_FUNCTION = "FROM #db.cdc.#function(?, ?, N'all update old')";
    private static final String GET_ALL_CHANGES_FOR_TABLE_FROM_DIRECT = "FROM #db.cdc.#table";
    private static final String GET_ALL_CHANGES_FOR_TABLE_FROM_FUNCTION_ORDER_BY = "ORDER BY [__$start_lsn] ASC, [__$seqval] ASC, [__$operation] ASC";
    private static final String GET_ALL_CHANGES_FOR_TABLE_FROM_DIRECT_ORDER_BY = "ORDER BY [__$start_lsn] ASC, [__$command_id] ASC, [__$seqval] ASC, [__$operation] ASC";

    /**
     * Queries the list of captured column names and their change table identifiers in the given database.
     */
    private static final String GET_CAPTURED_COLUMNS = "SELECT object_id, column_name" +
            " FROM #db.cdc.captured_columns" +
            " ORDER BY object_id, column_id";

    /**
     * Queries the list of capture instances in the given database.
     *
     * If two or more capture instances with the same start LSN are available for a given source table,
     * only the newest one will be returned.
     *
     * We use a query instead of {@code sys.sp_cdc_help_change_data_capture} because:
     *   1. The stored procedure doesn't allow filtering capture instances by start LSN.
     *   2. There is no way to use the result returned by a stored procedure in a query.
     */
    private static final String GET_CHANGE_TABLES = "WITH ordered_change_tables" +
            " AS (SELECT ROW_NUMBER() OVER (PARTITION BY ct.source_object_id, ct.start_lsn ORDER BY ct.create_date DESC) AS ct_sequence," +
            " ct.*" +
            " FROM #db.cdc.change_tables AS ct#)" +
            " SELECT OBJECT_SCHEMA_NAME(source_object_id, DB_ID(?))," +
            " OBJECT_NAME(source_object_id, DB_ID(?))," +
            " capture_instance," +
            " object_id," +
            " start_lsn" +
            " FROM ordered_change_tables WHERE ct_sequence = 1";

    private static final String GET_NEW_CHANGE_TABLES = "SELECT * FROM #db.cdc.change_tables WHERE start_lsn BETWEEN ? AND ?";
    private static final String GET_MIN_LSN_FROM_ALL_CHANGE_TABLES = "select min(start_lsn) from #db.cdc.change_tables";
    private static final String OPENING_QUOTING_CHARACTER = "[";
    private static final String CLOSING_QUOTING_CHARACTER = "]";

    private static final String URL_PATTERN = "jdbc:sqlserver://${" + JdbcConfiguration.HOSTNAME + "}";

    private final SqlServerConnectorConfig config;
    private final boolean useSingleDatabase;
    private final String getAllChangesForTable;
    private final int queryFetchSize;

    private final SqlServerDefaultValueConverter defaultValueConverter;

    private boolean optionRecompile;

    private static final Field AGENT_STATUS_QUERY = Field.create("sqlserver.agent.status.query")
            .withDescription("Query to get the running status of the SQL Server Agent")
            .withDefault(
                    "SELECT CASE WHEN dss.[status]=4 THEN 1 ELSE 0 END AS isRunning FROM #db.sys.dm_server_services dss WHERE dss.[servicename] LIKE N'SQL Server Agent (%';");

    /**
     * Creates a new connection using the supplied configuration.
     *
     * @param config              {@link Configuration} instance, may not be null.
     * @param valueConverters     {@link SqlServerValueConverters} instance
     * @param skippedOperations   a set of {@link Envelope.Operation} to skip in streaming
     */
    public SqlServerConnection(SqlServerConnectorConfig config, SqlServerValueConverters valueConverters,
                               Set<Envelope.Operation> skippedOperations,
                               boolean useSingleDatabase) {
        super(config.getJdbcConfig(), createConnectionFactory(config.getJdbcConfig(), useSingleDatabase), OPENING_QUOTING_CHARACTER, CLOSING_QUOTING_CHARACTER);

        defaultValueConverter = new SqlServerDefaultValueConverter(this::connection, valueConverters);
        this.queryFetchSize = config.getQueryFetchSize();

        getAllChangesForTable = buildGetAllChangesForTableQuery(config.getDataQueryMode(), skippedOperations);

        this.config = config;
        this.useSingleDatabase = useSingleDatabase;

        this.optionRecompile = false;
    }

    /**
     * Creates a new connection using the supplied configuration.
     *
     * @param config              {@link Configuration} instance, may not be null.
     * @param valueConverters     {@link SqlServerValueConverters} instance
     * @param skippedOperations   a set of {@link Envelope.Operation} to skip in streaming
     * @param optionRecompile     Includes query option RECOMPILE on incremental snapshots
     */
    public SqlServerConnection(SqlServerConnectorConfig config, SqlServerValueConverters valueConverters,
                               Set<Envelope.Operation> skippedOperations, boolean useSingleDatabase,
                               boolean optionRecompile) {
        this(config, valueConverters, skippedOperations, useSingleDatabase);

        this.optionRecompile = optionRecompile;
    }

    private String buildGetAllChangesForTableQuery(SqlServerConnectorConfig.DataQueryMode dataQueryMode,
                                                   Set<Envelope.Operation> skippedOperations) {
        String result = GET_ALL_CHANGES_FOR_TABLE_SELECT + " ";
        List<String> where = new LinkedList<>();
        switch (dataQueryMode) {
            case FUNCTION:
                result += GET_ALL_CHANGES_FOR_TABLE_FROM_FUNCTION + " ";
                break;
            case DIRECT:
                result += GET_ALL_CHANGES_FOR_TABLE_FROM_DIRECT + " ";
                break;
        }
        where.add("(([__$start_lsn] = ? AND [__$seqval] = ? AND [__$operation] > ?) " +
                "OR ([__$start_lsn] = ? AND [__$seqval] > ?) " +
                "OR ([__$start_lsn] > ?))");
        where.add("[__$start_lsn] <= ?");

        if (hasSkippedOperations(skippedOperations)) {
            Set<String> skippedOps = new HashSet<>();
            skippedOperations.forEach((Envelope.Operation operation) -> {
                // This number are the __$operation number in the SQLServer
                // https://docs.microsoft.com/en-us/sql/relational-databases/system-functions/cdc-fn-cdc-get-all-changes-capture-instance-transact-sql?view=sql-server-ver15#table-returned
                switch (operation) {
                    case CREATE:
                        skippedOps.add("2");
                        break;
                    case UPDATE:
                        skippedOps.add("3");
                        skippedOps.add("4");
                        break;
                    case DELETE:
                        skippedOps.add("1");
                        break;
                }
            });
            where.add("[__$operation] NOT IN (" + String.join(",", skippedOps) + ")");
        }

        if (!where.isEmpty()) {
            result += " WHERE " + String.join(" AND ", where) + " ";
        }

        switch (dataQueryMode) {
            case FUNCTION:
                result += GET_ALL_CHANGES_FOR_TABLE_FROM_FUNCTION_ORDER_BY;
                break;
            case DIRECT:
                result += GET_ALL_CHANGES_FOR_TABLE_FROM_DIRECT_ORDER_BY;
                break;
        }

        return result;
    }

    private boolean hasSkippedOperations(Set<Envelope.Operation> skippedOperations) {
        if (!skippedOperations.isEmpty()) {
            for (Envelope.Operation operation : skippedOperations) {
                switch (operation) {
                    case CREATE:
                    case UPDATE:
                    case DELETE:
                        return true;
                }
            }
        }
        return false;
    }

    private static ConnectionFactory createConnectionFactory(SqlServerJdbcConfiguration config, boolean useSingleDatabase) {
        return JdbcConnection.patternBasedFactory(createUrlPattern(config, useSingleDatabase),
                SQLServerDriver.class.getName(),
                SqlServerConnection.class.getClassLoader(),
                JdbcConfiguration.PORT.withDefault(SqlServerConnectorConfig.PORT.defaultValueAsString()));
    }

    @VisibleForTesting
    protected static String createUrlPattern(SqlServerJdbcConfiguration config, boolean useSingleDatabase) {
        String pattern = URL_PATTERN;
        if (config.getInstance() != null) {
            pattern += "\\" + config.getInstance();
            if (config.getPortAsString() != null) {
                pattern += ":${" + JdbcConfiguration.PORT + "}";
            }
        }
        else {
            pattern += ":${" + JdbcConfiguration.PORT + "}";
        }
        if (useSingleDatabase) {
            pattern += ";databaseName=${" + JdbcConfiguration.DATABASE + "}";
        }

        return pattern;
    }

    /**
     * The {@code [} character is used in SQL Server's extended pattern syntax to define character ranges or sets.
     *
     * @see <a href="https://learn.microsoft.com/en-us/sql/t-sql/language-elements/like-transact-sql#pattern">
     *     SQL Server LIKE pattern syntax</a>
     */
    @Override
    protected Set<Character> getLikeWildcardCharacters() {
        return Stream.concat(super.getLikeWildcardCharacters().stream(), Stream.of('['))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns a JDBC connection string for the current configuration.
     *
     * @return a {@code String} where the variables in {@code urlPattern} are replaced with values from the configuration
     */
    public String connectionString() {
        return connectionString(createUrlPattern(config.getJdbcConfig(), useSingleDatabase));
    }

    @Override
    public synchronized Connection connection(boolean executeOnConnect) throws SQLException {
        boolean connected = isConnected();
        Connection connection = super.connection(executeOnConnect);

        if (!connected) {
            connection.setAutoCommit(false);
        }

        return connection;
    }

    /**
     * @return the current largest log sequence number
     */
    public Lsn getMaxLsn(String databaseName) throws SQLException {
        return queryAndMap(replaceDatabaseNamePlaceholder(GET_MAX_LSN, databaseName), singleResultMapper(rs -> {
            final Lsn ret = Lsn.valueOf(rs.getBytes(1));
            LOGGER.trace("Current maximum lsn is {}", ret);
            return ret;
        }, "Maximum LSN query must return exactly one value"));
    }

    /**
     * @return the log sequence number of the most recent transaction
     *         that isn't further than {@code maxOffset} from the beginning.
     */
    public Lsn getNthTransactionLsnFromBeginning(String databaseName, int maxOffset) throws SQLException {
        return prepareQueryAndMap(
                replaceDatabaseNamePlaceholder(GET_NTH_TRANSACTION_LSN_FROM_BEGINNING, databaseName),
                statement -> statement.setInt(1, maxOffset),
                singleResultMapper(rs -> {
                    final Lsn ret = Lsn.valueOf(rs.getBytes(1));
                    LOGGER.trace("Nth lsn from beginning is {}", ret);
                    return ret;
                }, "Nth LSN query must return exactly one value"));
    }

    /**
     * @return the log sequence number of the most recent transaction
     *         that isn't further than {@code maxOffset} from {@code lastLsn}.
     */
    public Lsn getNthTransactionLsnFromLast(String databaseName, Lsn lastLsn, int maxOffset) throws SQLException {
        return prepareQueryAndMap(replaceDatabaseNamePlaceholder(GET_NTH_TRANSACTION_LSN_FROM_LAST, databaseName), statement -> {
            statement.setInt(1, maxOffset);
            statement.setBytes(2, lastLsn.getBinary());
        }, singleResultMapper(rs -> {
            final Lsn ret = Lsn.valueOf(rs.getBytes(1));
            LOGGER.trace("Nth lsn from last is {}", ret);
            return ret;
        }, "Nth LSN query must return exactly one value"));
    }

    /**
     * @return the log sequence number of the most recent transaction.
     */
    public Lsn getMaxTransactionLsn(String databaseName) throws SQLException {
        return queryAndMap(replaceDatabaseNamePlaceholder(GET_MAX_TRANSACTION_LSN, databaseName), singleResultMapper(rs -> {
            final Lsn ret = Lsn.valueOf(rs.getBytes(1));
            LOGGER.trace("Max transaction lsn is {}", ret);
            return ret;
        }, "Max transaction LSN query must return exactly one value"));
    }

    /**
     * @return the smallest log sequence number of table
     */
    public Lsn getMinLsn(String databaseName, String changeTableName) throws SQLException {
        String query = replaceDatabaseNamePlaceholder(GET_MIN_LSN, databaseName);
        return prepareQueryAndMap(query, preparer -> {
            preparer.setString(1, changeTableName);
        }, singleResultMapper(rs -> {
            final Lsn ret = Lsn.valueOf(rs.getBytes(1));
            LOGGER.trace("Current minimum lsn is {}", ret);
            return ret;
        }, "Minimum LSN query must return exactly one value"));
    }

    /**
     * Provides all changes recorder by the SQL Server CDC capture process for a set of tables.
     *
     * @param changeTable - the requested table to obtain changes for
     * @param intervalFromLsn - closed lower bound of interval of changes to be provided
     * @param seqvalFromLsn - in-transaction sequence value to start after, pass {@link Lsn#ZERO} to fetch all sequence values
     * @param operationFrom - operation number to start after, pass 0 to fetch all operations
     * @param intervalToLsn  - closed upper bound of interval  of changes to be provided
     * @param maxRows - the max number of rows to return, pass 0 for no limit
     * @throws SQLException
     */
    public ResultSet getChangesForTable(SqlServerChangeTable changeTable, Lsn intervalFromLsn, Lsn seqvalFromLsn, int operationFrom,
                                        Lsn intervalToLsn, int maxRows)
            throws SQLException {
        String databaseName = changeTable.getSourceTableId().catalog();
        String capturedColumns = changeTable.getCapturedColumns().stream().map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));

        String query = replaceDatabaseNamePlaceholder(getAllChangesForTable, databaseName)
                .replaceFirst(STATEMENTS_PLACEHOLDER, Matcher.quoteReplacement(capturedColumns));

        query = switch (config.getDataQueryMode()) {
            case FUNCTION ->
                query.replace(FUNCTION_NAME_PLACEHOLDER, quoteIdentifier(GET_ALL_CHANGES_FUNCTION_PREFIX.concat(changeTable.getCaptureInstance())));
            case DIRECT ->
                query.replace(TABLE_NAME_PLACEHOLDER, quoteIdentifier(changeTable.getChangeTableId().table()));
        };

        if (maxRows > 0) {
            query = query.replace("SELECT ", String.format("SELECT TOP %d ", maxRows));
        }

        // If the table was added in the middle of queried buffer we need
        // to adjust from to the first LSN available
        final Lsn fromLsn = getFromLsn(changeTable, intervalFromLsn);
        LOGGER.trace("Getting {} changes for table {} in range [{}-{}-{}, {}]", maxRows > 0 ? "top " + maxRows : "", changeTable, fromLsn, seqvalFromLsn, operationFrom,
                intervalToLsn);

        PreparedStatement statement = connection().prepareStatement(query);
        statement.closeOnCompletion();

        if (queryFetchSize > 0) {
            statement.setFetchSize(queryFetchSize);
        }

        int paramIndex = 1;
        if (config.getDataQueryMode() == SqlServerConnectorConfig.DataQueryMode.FUNCTION) {
            statement.setBytes(paramIndex++, fromLsn.getBinary());
            statement.setBytes(paramIndex++, intervalToLsn.getBinary());
        }
        statement.setBytes(paramIndex++, fromLsn.getBinary());
        statement.setBytes(paramIndex++, seqvalFromLsn.getBinary());
        statement.setInt(paramIndex++, operationFrom);
        statement.setBytes(paramIndex++, fromLsn.getBinary());
        statement.setBytes(paramIndex++, seqvalFromLsn.getBinary());
        statement.setBytes(paramIndex++, fromLsn.getBinary());
        statement.setBytes(paramIndex++, intervalToLsn.getBinary());

        return statement.executeQuery();
    }

    public ResultSet getChangesForTable(SqlServerChangeTable changeTable, Lsn intervalFromLsn, Lsn intervalToLsn, int maxRows) throws SQLException {
        return getChangesForTable(changeTable, intervalFromLsn, Lsn.ZERO, 0, intervalToLsn, maxRows);
    }

    public ResultSet getChangesForTable(SqlServerChangeTable changeTable, Lsn intervalFromLsn, Lsn intervalToLsn) throws SQLException {
        return getChangesForTable(changeTable, intervalFromLsn, intervalToLsn, 0);
    }

    private Lsn getFromLsn(SqlServerChangeTable changeTable, Lsn intervalFromLsn) throws SQLException {
        Lsn fromLsn = changeTable.getStartLsn().compareTo(intervalFromLsn) > 0 ? changeTable.getStartLsn() : intervalFromLsn;
        return fromLsn.getBinary() != null ? fromLsn : getMinLsn(changeTable.getSourceTableId().catalog(), changeTable.getCaptureInstance());
    }

    /**
     * Obtain the next available position in the database log.
     *
     * @param databaseName - the name of the database that the LSN belongs to
     * @param lsn - LSN of the current position
     * @return LSN of the next position in the database
     * @throws SQLException
     */
    public Lsn incrementLsn(String databaseName, Lsn lsn) throws SQLException {
        return prepareQueryAndMap(replaceDatabaseNamePlaceholder(INCREMENT_LSN, databaseName), statement -> {
            statement.setBytes(1, lsn.getBinary());
        }, singleResultMapper(rs -> {
            final Lsn ret = Lsn.valueOf(rs.getBytes(1));
            LOGGER.trace("Increasing lsn from {} to {}", lsn, ret);
            return ret;
        }, "Increment LSN query must return exactly one value"));
    }

    /**
     * Check if the user with which connection object is created has
     * access to CDC table.
     *
     * @return boolean indicating the presence/absence of access
     */
    public boolean checkIfConnectedUserHasAccessToCDCTable(String databaseName) throws SQLException {
        final AtomicBoolean userHasAccess = new AtomicBoolean();
        final String query = replaceDatabaseNamePlaceholder("EXEC #db.sys.sp_cdc_help_change_data_capture", databaseName);
        this.query(query, rs -> userHasAccess.set(rs.next()));
        return userHasAccess.get();
    }

    public List<SqlServerChangeTable> getChangeTables(String databaseName) throws SQLException {
        return getChangeTables(databaseName, Lsn.NULL);
    }

    public List<SqlServerChangeTable> getChangeTables(String databaseName, Lsn toLsn) throws SQLException {
        Map<Integer, List<String>> columns = queryAndMap(
                replaceDatabaseNamePlaceholder(GET_CAPTURED_COLUMNS, databaseName),
                rs -> {
                    Map<Integer, List<String>> result = new HashMap<>();
                    while (rs.next()) {
                        int changeTableObjectId = rs.getInt(1);
                        if (!result.containsKey(changeTableObjectId)) {
                            result.put(changeTableObjectId, new LinkedList<>());
                        }

                        result.get(changeTableObjectId).add(rs.getString(2));
                    }
                    return result;
                });
        final ResultSetMapper<List<SqlServerChangeTable>> mapper = rs -> {
            final List<SqlServerChangeTable> changeTables = new ArrayList<>();
            while (rs.next()) {
                int changeTableObjectId = rs.getInt(4);
                changeTables.add(
                        new SqlServerChangeTable(
                                new TableId(databaseName, rs.getString(1), rs.getString(2)),
                                rs.getString(3),
                                changeTableObjectId,
                                Lsn.valueOf(rs.getBytes(5)),
                                columns.get(changeTableObjectId)));
            }
            return changeTables;
        };

        String query = replaceDatabaseNamePlaceholder(GET_CHANGE_TABLES, databaseName);

        if (toLsn.isAvailable()) {
            return prepareQueryAndMap(query.replace(STATEMENTS_PLACEHOLDER, " WHERE ct.start_lsn <= ?"),
                    ps -> {
                        ps.setBytes(1, toLsn.getBinary());
                        ps.setString(2, databaseName);
                        ps.setString(3, databaseName);
                    },
                    mapper);
        }
        else {
            return prepareQueryAndMap(query.replace(STATEMENTS_PLACEHOLDER, ""),
                    ps -> {
                        ps.setString(1, databaseName);
                        ps.setString(2, databaseName);
                    },
                    mapper);
        }
    }

    public List<SqlServerChangeTable> getNewChangeTables(String databaseName, Lsn fromLsn, Lsn toLsn) throws SQLException {
        final String query = replaceDatabaseNamePlaceholder(GET_NEW_CHANGE_TABLES, databaseName);

        return prepareQueryAndMap(query,
                ps -> {
                    ps.setBytes(1, fromLsn.getBinary());
                    ps.setBytes(2, toLsn.getBinary());
                },
                rs -> {
                    final List<SqlServerChangeTable> changeTables = new ArrayList<>();
                    while (rs.next()) {
                        changeTables.add(new SqlServerChangeTable(
                                rs.getString(4),
                                rs.getInt(1),
                                Lsn.valueOf(rs.getBytes(5))));
                    }
                    return changeTables;
                });
    }

    public Table getTableSchemaFromTable(String databaseName, SqlServerChangeTable changeTable) throws SQLException {
        final DatabaseMetaData metadata = connection().getMetaData();

        List<Column> columns = new ArrayList<>();
        try (ResultSet rs = metadata.getColumns(
                databaseName,
                changeTable.getSourceTableId().schema(),
                changeTable.getSourceTableId().table(),
                null)) {
            while (rs.next()) {
                readTableColumn(rs, changeTable.getSourceTableId(), null).ifPresent(ce -> {
                    // Filter out columns not included in the change table.
                    if (changeTable.getCapturedColumns().contains(ce.name())) {
                        columns.add(ce.create());
                    }
                });
            }
        }

        final List<String> pkColumnNames = readPrimaryKeyOrUniqueIndexNames(metadata, changeTable.getSourceTableId()).stream()
                .filter(column -> changeTable.getCapturedColumns().contains(column))
                .collect(Collectors.toList());
        Collections.sort(columns);
        return Table.editor()
                .tableId(changeTable.getSourceTableId())
                .addColumns(columns)
                .setPrimaryKeyNames(pkColumnNames)
                .create();
    }

    public String getNameOfChangeTable(String captureName) {
        return captureName + "_CT";
    }

    /**
     * Retrieve the name of the database in the original case as it's defined on the server.
     *
     * Although SQL Server supports case-insensitive collations, the connector uses the database name to build the
     * produced records' source info and, subsequently, the keys of its committed offset messages. This value
     * must remain the same during the lifetime of the connector regardless of the case used in the connector
     * configuration.
     */
    public String retrieveRealDatabaseName(String databaseName) {
        try {
            return prepareQueryAndMap(GET_DATABASE_NAME,
                    ps -> ps.setString(1, databaseName),
                    singleResultMapper(rs -> rs.getString(1), "Could not retrieve exactly one database name"));
        }
        catch (SQLException e) {
            throw new RuntimeException("Couldn't obtain database name", e);
        }
    }

    @Override
    protected boolean isTableUniqueIndexIncluded(String indexName, String columnName) {
        // SQL Server provides indices also without index name
        // so we need to ignore them
        return indexName != null;
    }

    @Override
    public Object getColumnValue(ResultSet rs, int columnIndex, Column column, Table table) throws SQLException {
        final ResultSetMetaData metaData = rs.getMetaData();
        final int columnType = metaData.getColumnType(columnIndex);

        if (columnType == Types.TIME) {
            return rs.getTimestamp(columnIndex);
        }
        else {
            return super.getColumnValue(rs, columnIndex, column, table);
        }
    }

    // NOTE: fix for DBZ-7359
    @Override
    public void setQueryColumnValue(PreparedStatement statement, Column column, int pos, Object value) throws SQLException {
        if (column.typeUsesCharset()) {
            // For mappings between sqlserver and JDBC types see -
            // https://learn.microsoft.com/en-us/sql/connect/jdbc/using-basic-data-types?view=sql-server-ver16
            // For details on the methods to use with respect to the `sendStringParametersAsUnicode` JDBC property, see -
            // https://learn.microsoft.com/en-us/sql/connect/jdbc/setting-the-connection-properties?view=sql-server-ver16
            // "An application should use the setNString, setNCharacterStream, and setNClob national character methods
            // of the SQLServerPreparedStatement and SQLServerCallableStatement classes for the NCHAR, NVARCHAR, and
            // LONGNVARCHAR JDBC data types."
            switch (column.jdbcType()) {
                case Types.NCHAR:
                    if (value instanceof String) {
                        statement.setNString(pos, (String) value);
                    }
                    else {
                        // not set, fall back on default implementation.
                        super.setQueryColumnValue(statement, column, pos, value);
                    }
                    break;
                case Types.NVARCHAR:
                    if (value instanceof String) {
                        statement.setNCharacterStream(pos, new StringReader((String) value));
                    }
                    else if (value instanceof Reader) {
                        statement.setNCharacterStream(pos, (Reader) value);
                    }
                    else {
                        // not set, fall back on default implementation.
                        super.setQueryColumnValue(statement, column, pos, value);
                    }
                    break;
                case Types.LONGNVARCHAR:
                    if (value instanceof String) {
                        // we'll fall back on nvarchar handling
                        statement.setNCharacterStream(pos, new StringReader((String) value));
                    }
                    else if (value instanceof Reader) {
                        // we'll fall back on nvarchar handling
                        statement.setNCharacterStream(pos, (Reader) value);
                    }
                    else if (value instanceof NClob) {
                        statement.setNClob(pos, (NClob) value);
                    }
                    else {
                        // not set, fall back on default implementation.
                        super.setQueryColumnValue(statement, column, pos, value);
                    }
                    break;
                default:
                    // not set, fall back on default implementation.
                    super.setQueryColumnValue(statement, column, pos, value);
                    break;
            }
        }
        else {
            // not set, fall back on default implementation.
            super.setQueryColumnValue(statement, column, pos, value);
        }
    }

    @Override
    public String buildSelectWithRowLimits(TableId tableId, int limit, String projection, Optional<String> condition,
                                           Optional<String> additionalCondition, String orderBy) {
        final StringBuilder sql = new StringBuilder("SELECT TOP ");
        sql
                .append(limit)
                .append(' ')
                .append(projection)
                .append(" FROM ");
        sql.append(quotedTableIdString(tableId));
        if (condition.isPresent()) {
            sql
                    .append(" WHERE ")
                    .append(condition.get());
            if (additionalCondition.isPresent()) {
                sql.append(" AND ");
                sql.append(additionalCondition.get());
            }
        }
        else if (additionalCondition.isPresent()) {
            sql.append(" WHERE ");
            sql.append(additionalCondition.get());
        }
        sql
                .append(" ORDER BY ")
                .append(orderBy);
        if (this.optionRecompile) {
            sql
                    .append(" OPTION(RECOMPILE)");
        }
        return sql.toString();
    }

    @Override
    public Optional<Boolean> nullsSortLast() {
        // "Null values are treated as the lowest possible values"
        // https://learn.microsoft.com/en-us/sql/t-sql/queries/select-order-by-clause-transact-sql?view=sql-server-ver16
        return Optional.of(false);
    }

    @Override
    public String quotedTableIdString(TableId tableId) {
        return Stream.of(tableId.catalog(), tableId.schema(), tableId.table())
                .map(this::quoteIdentifier)
                .collect(Collectors.joining("."));
    }

    private String replaceDatabaseNamePlaceholder(String sql, String databaseName) {
        return sql.replace(DATABASE_NAME_PLACEHOLDER, quoteIdentifier(databaseName));
    }

    public SqlServerDefaultValueConverter getDefaultValueConverter() {
        return defaultValueConverter;
    }

    public boolean isAgentRunning(String databaseName) throws SQLException {
        final String query = replaceDatabaseNamePlaceholder(config().getString(AGENT_STATUS_QUERY), databaseName);
        return queryAndMap(query,
                singleResultMapper(rs -> rs.getBoolean(1), "SQL Server Agent running status query must return exactly one value"));
    }

    @Override
    public Optional<Instant> getCurrentTimestamp() throws SQLException {
        return queryAndMap("SELECT SYSDATETIMEOFFSET()",
                rs -> rs.next() ? Optional.of(rs.getObject(1, OffsetDateTime.class).toInstant()) : Optional.empty());
    }

    public boolean validateLogPosition(Partition partition, OffsetContext offset, CommonConnectorConfig config) {

        final Lsn storedLsn = ((SqlServerOffsetContext) offset).getChangePosition().getCommitLsn();

        final String oldestFirstChangeQuery = replaceDatabaseNamePlaceholder(GET_MIN_LSN_FROM_ALL_CHANGE_TABLES, ((SqlServerPartition) partition).getDatabaseName());

        try {

            final String oldestScn = singleOptionalValue(oldestFirstChangeQuery, rs -> rs.getString(1));

            if (oldestScn == null) {
                return false;
            }

            LOGGER.info("Oldest SCN in logs is '{}'", oldestScn);
            LOGGER.info("Stored LSN is '{}'", storedLsn);
            return storedLsn == null || Lsn.NULL.equals(storedLsn) || Lsn.valueOf(oldestScn).compareTo(storedLsn) < 0;
        }
        catch (SQLException e) {
            throw new DebeziumException("Unable to get last available log position", e);
        }
    }

    public <T> T singleOptionalValue(String query, ResultSetExtractor<T> extractor) throws SQLException {
        return queryAndMap(query, rs -> rs.next() ? extractor.apply(rs) : null);
    }
}
