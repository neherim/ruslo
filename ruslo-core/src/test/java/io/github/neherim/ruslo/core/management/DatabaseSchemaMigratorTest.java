package io.github.neherim.ruslo.core.management;

import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.spi.PlainJdbcDatabase;
import io.github.neherim.ruslo.core.TestDatabases;
import org.junit.jupiter.api.Test;

import java.util.List;
import javax.sql.DataSource;

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
