package io.github.neherim.ruslo.core.management;

import io.github.neherim.ruslo.core.TestDatabases;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.database.DatabaseDialect;
import io.github.neherim.ruslo.core.spi.PlainJdbcDatabase;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

class DatabaseSchemaMigratorTest {

    @Test
    void shouldCreateSchemaOnFirstRun() {
        var database = new PlainJdbcDatabase(dataSource());

        DatabaseSchemaMigrator.update(database.getDataSource());

        assertEquals(1, schemaVersion(database));
        assertEquals(1, tableRowCount(database, "xs_schema_version"));
        assertEquals(0, tableRowCount(database, "xs_process_instance"));
        assertEquals(0, tableRowCount(database, "xs_process_state_hist"));
        assertEquals(1, columnCount(database, "lease_owner"));
        assertEquals(1, columnCount(database, "row_version"));
        assertEquals(0, columnCount(database, "lease_token"));
    }

    @Test
    void shouldCreateIndexesWithTableAndColumnNames() throws SQLException {
        var dataSource = dataSource();

        DatabaseSchemaMigrator.update(dataSource);

        var processIndexes = indexNames(dataSource, "xs_process_instance");
        assertThat(processIndexes).contains(
            "xs_process_instance_lease_owner_idx",
            "xs_process_instance_business_key_idx",
            "xs_process_instance_worker_id_next_run_at_id_idx");
        if (TestDatabases.dialect() == DatabaseDialect.POSTGRESQL) {
            assertThat(processIndexes).contains("xs_process_instance_status_idx");
        }
        assertThat(indexNames(dataSource, "xs_process_state_hist"))
            .contains("xs_process_state_hist_link_process_instance_idx");
    }

    @Test
    void shouldSkipAlreadyAppliedMigrations() {
        var database = new PlainJdbcDatabase(dataSource());

        DatabaseSchemaMigrator.update(database.getDataSource());
        DatabaseSchemaMigrator.update(database.getDataSource());

        assertEquals(1, schemaVersion(database));
        assertEquals(1, tableRowCount(database, "xs_schema_version"));
    }

    @Test
    void shouldStartEngineWithAutomaticSchemaMigration() {
        var database = new PlainJdbcDatabase(dataSource());

        var processEngine = new ProcessEngineBuilder(database)
            .withDispatcherEnabled(false)
            .build()
            .start();

        assertEquals(1, schemaVersion(database));
        processEngine.close();
    }

    @Test
    void shouldSplitStatementsBySemicolonAndSkipBlankOnes() {
        var statements = DatabaseSchemaMigrator.splitStatements("""
                ;
                create table first (id int);
                ;;
                    create table second (id int)  ;

                """);

        assertIterableEquals(List.of(
                "create table first (id int)",
                "create table second (id int)"), statements);
    }

    private static int schemaVersion(PlainJdbcDatabase database) {
        return database.queryForObject("select version from xs_schema_version where id = 1", rs -> rs.getInt(1))
            .orElseThrow();
    }

    private static Set<String> indexNames(DataSource dataSource, String tableName) throws SQLException {
        var names = new HashSet<String>();
        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            var table = metadata.storesUpperCaseIdentifiers() ? tableName.toUpperCase(Locale.ROOT) : tableName;
            try (var indexes = metadata.getIndexInfo(null, connection.getSchema(), table, false, true)) {
                while (indexes.next()) {
                    var name = indexes.getString("INDEX_NAME");
                    if (name != null) {
                        names.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return names;
    }

    private static int tableRowCount(PlainJdbcDatabase database, String tableName) {
        return database.queryForObject("select count(*) from " + tableName, rs -> rs.getInt(1))
            .orElseThrow();
    }

    private static int columnCount(PlainJdbcDatabase database, String columnName) {
        return database.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = current_schema
                  and lower(table_name) = 'xs_process_instance' and lower(column_name) = ?
                """, rs -> rs.getInt(1), columnName).orElseThrow();
    }

    private static DataSource dataSource() {
        return TestDatabases.newDataSource();
    }
}
