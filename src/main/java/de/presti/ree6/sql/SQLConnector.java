package de.presti.ree6.sql;

import com.google.gson.JsonElement;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.presti.ree6.main.Main;
import de.presti.ree6.sql.base.entities.SQLEntity;
import de.presti.ree6.sql.base.entities.StoredResultSet;
import de.presti.ree6.sql.base.utils.MigrationUtil;
import de.presti.ree6.sql.base.utils.SQLUtil;
import de.presti.ree6.sql.mapper.EntityMapper;
import de.presti.ree6.sql.seed.SeedManager;
import lombok.extern.slf4j.Slf4j;
import org.reflections.Reflections;

import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Types;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A "Connector" Class which connect with the used Database Server.
 * Used to manage the connection between Server and Client.
 */
@Slf4j
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
public class SQLConnector {

    // Various String that keep connection information to use for a connection.
    private final String databaseUser,
            databaseName,
            databasePassword,
            databaseServerIP;

    // The port of the Server.
    private final int databaseServerPort;

    // An Instance of the actual Java SQL Connection.
    private HikariDataSource dataSource;

    // An Instance of the SQL-Worker which works with the Data in the Database.
    private final SQLWorker sqlWorker;

    // An Instance of the EntityMapper which is used to map the Data into classes.
    private final EntityMapper entityMapper;

    // A boolean to keep track if there was at least one valid connection.
    private boolean connectedOnce = false;

    // A HashMap with every Table Name as key and the values as value.
    private final Map<String, String> tables = new HashMap<>();

    /**
     * Constructor with the needed data to open an SQL connection.
     *
     * @param databaseUser       the Database Username
     * @param databaseName       the Database name
     * @param databasePassword   the Database User password
     * @param databaseServerIP   the Address of the Database Server.
     * @param databaseServerPort the Port of the Database Server.
     */
    public SQLConnector(String databaseUser, String databaseName, String databasePassword, String databaseServerIP, int databaseServerPort) {
        this.databaseUser = databaseUser;
        this.databaseName = databaseName;
        this.databasePassword = databasePassword;
        this.databaseServerIP = databaseServerIP;
        this.databaseServerPort = databaseServerPort;

        sqlWorker = new SQLWorker(this);
        entityMapper = new EntityMapper();

        connectToSQLServer();
        createTables();
        try {
            MigrationUtil.runAllMigrations(this);
        } catch (Exception exception) {
            log.error("Error while running Migrations!", exception);
        }

        SeedManager.runAllSeeds(this);
    }

    /**
     * Try to open a connection to the SQL Server with the given data.
     */
    public void connectToSQLServer() {
        log.info("Connecting to SQl-Service (SQL).");
        // Check if there is already an open Connection.
        if (isConnected()) {
            try {
                // Close if there is and notify.
                getDataSource().close();
                log.info("Service (SQL) has been stopped.");
            } catch (Exception ignore) {
                // Notify if there was an error.
                log.error("Service (SQL) couldn't be stopped.");
            }
        }

        try {
            HikariConfig hConfig = new HikariConfig();

            String jdbcUrl;

            switch (Main.getInstance().getConfig().getConfiguration().getString("hikari.misc.storage").toLowerCase()) {
                case "mariadb" -> {
                    jdbcUrl = "jdbc:mariadb://%s:%s/%s?user=%s&password=%s";
                    jdbcUrl = jdbcUrl.formatted(databaseServerIP,
                            databaseServerPort,
                            databaseName,
                            databaseUser,
                            databasePassword);
                }

                case "sqlite" -> {
                    jdbcUrl = "jdbc:sqlite:%s";
                    jdbcUrl = jdbcUrl.formatted("storage/Ree6.db");
                }

                default -> {
                    jdbcUrl = "jdbc:h2:%s";
                    jdbcUrl = jdbcUrl.formatted("./storage/Ree6.db");
                }
            }

            hConfig.setJdbcUrl(jdbcUrl);
            hConfig.setMaximumPoolSize(Main.getInstance().getConfig().getConfiguration().getInt("hikari.misc.poolSize"));
            dataSource = new HikariDataSource(hConfig);
            log.info("Service (SQL) has been started. Connection was successful.");
            connectedOnce = true;
        } catch (Exception exception) {
            // Notify if there was an error.
            log.error("Service (SQL) couldn't be started. Connection was unsuccessful.", exception);
        }
    }

    /**
     * Create Tables in the Database if they aren't already set.
     */
    public void createTables() {

        // Check if there is an open Connection if not, skip.
        if (!isConnected()) return;

        // Registering the tables and values.
        tables.putIfAbsent("Opt_out", "(GID VARCHAR(40), UID VARCHAR(40))");
        tables.putIfAbsent("Migrations", "(NAME VARCHAR(100), DATE VARCHAR(100))");
        tables.putIfAbsent("Seeds", "(VERSION VARCHAR(100), DATE VARCHAR(100))");

        // Iterating through all table presets.
        for (Map.Entry<String, String> entry : tables.entrySet()) {
            querySQL("CREATE TABLE IF NOT EXISTS " + entry.getKey() + entry.getValue());
        }

        Reflections reflections = new Reflections("de.presti.ree6");
        Set<Class<? extends SQLEntity>> classes = reflections.getSubTypesOf(SQLEntity.class);
        for (Class<? extends SQLEntity> aClass : classes) {
            log.info("Creating Table {}", aClass.getSimpleName());
            // Create a Table based on the key.
            try {
                if (!sqlWorker.createTable(aClass)) {
                    log.warn("Couldn't create {} Table.", aClass.getSimpleName());
                }
            } catch (Exception exception) {

                // Notify if there was an error.
                log.error("Couldn't create " + aClass.getSimpleName() + " Table.", exception);
            }
        }
    }

    //region Utility

    /**
     * Send an SQL-Query to SQL-Server and get the response.
     *
     * @param sqlQuery    the SQL-Query.
     * @param objcObjects the Object in the Query.
     * @return The Result from the SQL-Server.
     */
    public StoredResultSet querySQL(String sqlQuery, Object... objcObjects) {
        if (!isConnected()) {
            if (connectedOnce()) {
                connectToSQLServer();
                return querySQL(sqlQuery, objcObjects);
            } else {
                return new StoredResultSet();
            }
        }

        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;
        try (Connection connection = getDataSource().getConnection()) {
            preparedStatement = connection.prepareStatement(sqlQuery, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
            int index = 1;

            for (Object obj : objcObjects) {
                if (obj instanceof String) {
                    preparedStatement.setObject(index++, obj, Types.VARCHAR);
                } else if (obj instanceof Blob) {
                    preparedStatement.setObject(index++, obj, Types.BLOB);
                } else if (obj instanceof Integer) {
                    preparedStatement.setObject(index++, obj, Types.INTEGER);
                } else if (obj instanceof Long) {
                    preparedStatement.setObject(index++, obj, Types.BIGINT);
                } else if (obj instanceof Float) {
                    preparedStatement.setObject(index++, obj, Types.FLOAT);
                } else if (obj instanceof Double) {
                    preparedStatement.setObject(index++, obj, Types.DOUBLE);
                } else if (obj instanceof Boolean) {
                    preparedStatement.setObject(index++, obj, Types.BOOLEAN);
                } else if (obj instanceof JsonElement jsonElement) {
                    preparedStatement.setObject(index++, SQLUtil.convertJSONToBlob(jsonElement), Types.BLOB);
                } else if (obj instanceof byte[] byteArray) {
                    preparedStatement.setObject(index++, Base64.getEncoder().encodeToString(byteArray), Types.VARCHAR);
                } else if (obj instanceof Date date) {
                    preparedStatement.setObject(index++, date.getTime(), Types.BIGINT);
                } else if (obj == null) {
                    preparedStatement.setNull(index++, Types.NULL);
                }
            }

            if (sqlQuery.toUpperCase().startsWith("SELECT")) {
                resultSet = preparedStatement.executeQuery();
                StoredResultSet storedResultSet = new StoredResultSet();

                storedResultSet.setColumns(resultSet.getMetaData().getColumnCount());
                resultSet.last();
                storedResultSet.setRows(resultSet.getRow());
                resultSet.beforeFirst();

                for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                    storedResultSet.addColumn(i, resultSet.getMetaData().getColumnName(i));
                }

                if (storedResultSet.hasResults()) {
                    while (resultSet.next()) {
                        for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                            storedResultSet.setValue(resultSet.getRow() - 1, i, resultSet.getObject(i));
                        }
                    }
                }
                resultSet.close();
                return storedResultSet;
            } else {
                preparedStatement.executeUpdate();
                return null;
            }
        } catch (SQLNonTransientConnectionException exception) {
            if (connectedOnce()) {
                log.error("Couldn't send Query to SQL-Server, most likely a connection Issue", exception);
                connectToSQLServer();
                return querySQL(sqlQuery, objcObjects);
            }
        } catch (Exception exception) {
            log.error("Couldn't send Query to SQL-Server ( " + sqlQuery + " )", exception);
        } finally {
            try {
                if (preparedStatement != null)
                    preparedStatement.close();

                if (resultSet != null)
                    resultSet.close();
            } catch (Exception ignore) {
            }
        }

        return null;
    }

    //endregion

    /**
     * Check if there is an open connection to the Database Server.
     *
     * @return boolean If the connection is opened.
     */
    public boolean isConnected() {
        try {
            return getDataSource() != null && !getDataSource().isClosed();
        } catch (Exception ignore) {
        }

        return false;
    }

    /**
     * Call to close the current Connection.
     */
    public void close() {
        // Check if there is already an open Connection.
        if (isConnected()) {
            try {
                // Close if there is and notify.
                getDataSource().close();
                log.info("Service (SQL) has been stopped.");
            } catch (Exception ignore) {
                // Notify if there was an error.
                log.error("Service (SQL) couldn't be stopped.");
            }
        }
    }

    /**
     * Retrieve an Instance of the SQL-Connection.
     *
     * @return DataSource Instance of te SQL-Connection.
     */
    public HikariDataSource getDataSource() {
        return dataSource;
    }

    /**
     * Retrieve an Instance of the SQL-Worker to work with the Data.
     *
     * @return {@link SQLWorker} the Instance saved in this SQL-Connector.
     */
    public SQLWorker getSqlWorker() {
        return sqlWorker;
    }

    /**
     * Retrieve an Instance of the entity-Mapper to work with the Data.
     *
     * @return {@link EntityMapper} the Instance saved in this SQL-Connector.
     */
    public EntityMapper getEntityMapper() {
        return entityMapper;
    }

    /**
     * Check if there was at least one successful Connection to the Database Server.
     *
     * @return boolean If there was at least one successful Connection.
     */
    public boolean connectedOnce() {
        return connectedOnce;
    }
}