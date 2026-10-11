package io.github.neherim.ruslo.core.management;

import io.github.neherim.ruslo.core.api.DatabaseDialect;
import io.github.neherim.ruslo.core.api.exception.DatabaseMigrationException;
import io.github.neherim.ruslo.core.spi.DatabaseDialectResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/** Applies the built-in SQL migrations of the ruslo schema. */
public class DatabaseSchemaMigrator {
    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaMigrator.class);
    private static final int VERSION_ROW_ID = 1;
    private static final String MIGRATIONS_BASE_PATH = "db/migration";
    private static final Pattern MIGRATION_FILE_PATTERN = Pattern.compile("V(\\d+)__([A-Za-z0-9_-]+)\\.sql");

    /** Applies the built-in migrations, detecting the dialect from the data source. */
    public static void update(DataSource dataSource) {
        DatabaseDialect dialect;
        try {
            dialect = DatabaseDialectResolver.resolve(dataSource);
        } catch (IllegalStateException ex) {
            throw new DatabaseMigrationException(ex.getMessage(), ex);
        }
        update(dataSource, dialect);
    }

    static void update(DataSource dataSource, DatabaseDialect dialect) {
        Objects.requireNonNull(dataSource, "DataSource must not be null");
        Objects.requireNonNull(dialect, "Database dialect must not be null");
        try (var connection = dataSource.getConnection()) {
            var autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                log.info("Starting database migration...");
                initializeSchemaVersion(connection, dialect);
                var currentVersion = lockAndReadVersion(connection);
                for (var migration : findMigrations(dialect)) {
                    if (migration.version() > currentVersion) {
                        applyMigration(connection, migration);
                        currentVersion = migration.version();
                    }
                }
                connection.commit();
                log.info("Database migration completed successfully");
            } catch (SQLException | RuntimeException e) {
                rollback(connection, e);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new DatabaseMigrationException("Error during database migration", e);
        }
    }

    private static void initializeSchemaVersion(Connection connection, DatabaseDialect dialect) throws SQLException {
        execute(connection, """
            create table if not exists xs_schema_version (
                id int not null primary key,
                version int not null
            )
            """);
        execute(connection, insertInitialVersionSql(dialect));
    }

    private static int lockAndReadVersion(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
            "select version from xs_schema_version where id = ? for update")) {
            statement.setInt(1, VERSION_ROW_ID);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new DatabaseMigrationException("Database schema version row was not initialized");
                }
                return resultSet.getInt("version");
            }
        }
    }

    private static void applyMigration(Connection connection, Migration migration)
        throws SQLException {
        log.info("Applying database migration V{}", migration.version());
        for (var statement : loadStatements(migration)) {
            execute(connection, statement);
        }
        updateVersion(connection, migration.version());
    }

    private static String[] loadStatements(Migration migration) {
        var migrationSql = loadMigrationSql(migration);
        return splitStatements(migrationSql).toArray(String[]::new);
    }

    static List<String> splitStatements(String migrationSql) {
        return Arrays.stream(migrationSql.split(";"))
            .map(String::trim)
            .filter(sql -> !sql.isBlank())
            .toList();
    }

    private static String loadMigrationSql(Migration migration) {
        try (var stream = DatabaseSchemaMigrator.class.getClassLoader().getResourceAsStream(migration.resourcePath())) {
            if (stream == null) {
                throw new DatabaseMigrationException("Database migration resource not found: " + migration.resourcePath());
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatabaseMigrationException("Error reading database migration resource: " + migration.resourcePath(), e);
        }
    }

    private static List<Migration> findMigrations(DatabaseDialect dialect) {
        var directory = MIGRATIONS_BASE_PATH + "/" + resourceDirectory(dialect);
        try {
            var migrations = new ArrayList<Migration>();
            var resources = DatabaseSchemaMigrator.class.getClassLoader().getResources(directory);
            while (resources.hasMoreElements()) {
                migrations.addAll(findMigrations(directory, resources.nextElement()));
            }
            validateMigrations(dialect, migrations);
            migrations.sort(Comparator.comparingInt(Migration::version));
            return migrations;
        } catch (IOException e) {
            throw new DatabaseMigrationException("Error reading database migrations from " + directory, e);
        }
    }

    private static List<Migration> findMigrations(String directory, URL resource) {
        return switch (resource.getProtocol()) {
            case "file" -> findFileSystemMigrations(directory, resource);
            case "jar" -> findJarMigrations(directory, resource);
            default -> throw new DatabaseMigrationException("Unsupported migration resource protocol: " + resource);
        };
    }

    private static List<Migration> findFileSystemMigrations(String directory, URL resource) {
        try (var paths = Files.list(Path.of(resource.toURI()))) {
            return paths
                .filter(Files::isRegularFile)
                .map(path -> parseMigration(directory, path.getFileName().toString()))
                .flatMap(Optional::stream)
                .toList();
        } catch (IOException | URISyntaxException e) {
            throw new DatabaseMigrationException("Error reading filesystem database migrations from " + resource, e);
        }
    }

    private static List<Migration> findJarMigrations(String directory, URL resource) {
        try {
            var connection = (JarURLConnection) resource.openConnection();
            try (var jarFile = connection.getJarFile()) {
                return findJarMigrations(directory, jarFile);
            }
        } catch (IOException e) {
            throw new DatabaseMigrationException("Error reading jar database migrations from " + resource, e);
        }
    }

    private static List<Migration> findJarMigrations(String directory, JarFile jarFile) {
        var prefix = directory + "/";
        return jarFile.stream()
            .filter(entry -> !entry.isDirectory())
            .map(ZipEntry::getName)
            .filter(name -> name.startsWith(prefix))
            .filter(name -> name.indexOf('/', prefix.length()) < 0)
            .map(name -> parseMigration(directory, name.substring(prefix.length())))
            .flatMap(Optional::stream)
            .toList();
    }

    private static Optional<Migration> parseMigration(String directory, String fileName) {
        var matcher = MIGRATION_FILE_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        var version = Integer.parseInt(matcher.group(1));
        return Optional.of(new Migration(version, directory + "/" + fileName));
    }

    private static void validateMigrations(DatabaseDialect dialect, List<Migration> migrations) {
        if (migrations.isEmpty()) {
            throw new DatabaseMigrationException("No database migrations found for " + dialect);
        }

        var versions = new HashSet<Integer>();
        for (var migration : migrations) {
            if (!versions.add(migration.version())) {
                throw new DatabaseMigrationException("Duplicate database migration version: " + migration.version());
            }
        }
    }

    private static void updateVersion(Connection connection, int version) throws SQLException {
        try (var statement = connection.prepareStatement("update xs_schema_version set version = ? where id = ?")) {
            statement.setInt(1, version);
            statement.setInt(2, VERSION_ROW_ID);
            var updated = statement.executeUpdate();
            if (updated != 1) {
                throw new DatabaseMigrationException("Database schema version row was not updated");
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String insertInitialVersionSql(DatabaseDialect dialect) {
        return switch (dialect) {
            case POSTGRESQL -> "insert into xs_schema_version (id, version) values (1, 0) on conflict (id) do nothing";
            case H2 -> """
                insert into xs_schema_version (id, version)
                select 1, 0
                where not exists (select 1 from xs_schema_version where id = 1)
                """;
        };
    }

    private static String resourceDirectory(DatabaseDialect dialect) {
        return switch (dialect) {
            case POSTGRESQL -> "postgresql";
            case H2 -> "h2";
        };
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            failure.addSuppressed(rollbackError);
        }
    }

    private record Migration(int version, String resourcePath) {
    }

}
