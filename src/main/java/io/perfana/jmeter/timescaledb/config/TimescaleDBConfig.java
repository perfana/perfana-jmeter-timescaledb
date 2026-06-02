package io.perfana.jmeter.timescaledb.config;

import org.apache.jmeter.visualizers.backend.BackendListenerContext;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Configuration class for TimescaleDB connection settings.
 * Supports JMeter property substitution via ${__P(propertyName,defaultValue)}.
 */
public class TimescaleDBConfig {

    // Connection settings
    private String host;
    private int port;
    private String database;
    private String schema;
    private String user;
    private String password;

    // SSL settings
    private String sslMode;

    // Connection pool settings
    private int maxPoolSize;
    private int connectionTimeout;

    // Batch settings
    private int batchSize;
    private int flushInterval;

    // Test identification
    private String runId;
    private String location;
    private String nodeName;
    private String systemUnderTest;
    private String testEnvironment;

    // Mode settings
    private boolean syntheticMonitoring;
    private String scenarioName;

    // Data capture settings
    private boolean saveResponseBody;

    // URL normalization settings
    private boolean normalizeUrls;

    // Transaction flattening settings
    private boolean flattenNestedTransactions;

    // Session variable capture settings
    private boolean saveSessionVariables;
    private Set<String> sessionVariablesExclude;
    private int sessionVariablesMaxValueLength;
    private int sessionVariablesMaxTotalBytes;

    // Configuration keys
    public static final String KEY_HOST = "timescaleDBHost";
    public static final String KEY_PORT = "timescaleDBPort";
    public static final String KEY_DATABASE = "timescaleDBDatabase";
    public static final String KEY_SCHEMA = "timescaleDBSchema";
    public static final String KEY_USER = "timescaleDBUser";
    public static final String KEY_PASSWORD = "timescaleDBPassword";
    public static final String KEY_SSL_MODE = "timescaleDBSslMode";
    public static final String KEY_MAX_POOL_SIZE = "timescaleDBMaxPoolSize";
    public static final String KEY_CONNECTION_TIMEOUT = "timescaleDBConnectionTimeout";
    public static final String KEY_BATCH_SIZE = "timescaleDBBatchSize";
    public static final String KEY_FLUSH_INTERVAL = "timescaleDBFlushInterval";
    public static final String KEY_RUN_ID = "runId";
    public static final String KEY_LOCATION = "location";
    public static final String KEY_NODE_NAME = "nodeName";
    public static final String KEY_SYSTEM_UNDER_TEST = "systemUnderTest";
    public static final String KEY_TEST_ENVIRONMENT = "testEnvironment";
    public static final String KEY_SYNTHETIC_MONITORING = "syntheticMonitoring";
    public static final String KEY_SCENARIO_NAME = "scenarioName";
    public static final String KEY_SAVE_RESPONSE_BODY = "saveResponseBody";
    public static final String KEY_NORMALIZE_URLS = "normalizeUrls";
    public static final String KEY_FLATTEN_NESTED_TRANSACTIONS = "flattenNestedTransactions";
    public static final String KEY_SAVE_SESSION_VARIABLES = "saveSessionVariables";
    public static final String KEY_SESSION_VARIABLES_EXCLUDE = "sessionVariablesExclude";
    public static final String KEY_SESSION_VARIABLES_MAX_VALUE_LENGTH = "sessionVariablesMaxValueLength";
    public static final String KEY_SESSION_VARIABLES_MAX_TOTAL_BYTES = "sessionVariablesMaxTotalBytes";

    // Default values
    public static final String DEFAULT_HOST = "localhost";
    public static final String DEFAULT_PORT = "5432";
    public static final String DEFAULT_DATABASE = "jmeter";
    public static final String DEFAULT_SCHEMA = "public";
    public static final String DEFAULT_USER = "";
    public static final String DEFAULT_PASSWORD = "";
    public static final String DEFAULT_SSL_MODE = "prefer";
    public static final String DEFAULT_MAX_POOL_SIZE = "10";
    public static final String DEFAULT_CONNECTION_TIMEOUT = "30000";
    public static final String DEFAULT_BATCH_SIZE = "1000";
    public static final String DEFAULT_FLUSH_INTERVAL = "1";
    public static final String DEFAULT_RUN_ID = "Run";
    public static final String DEFAULT_LOCATION = "local";
    public static final String DEFAULT_NODE_NAME = "controller";
    public static final String DEFAULT_SYSTEM_UNDER_TEST = "SUT";
    public static final String DEFAULT_TEST_ENVIRONMENT = "test";
    public static final String DEFAULT_SYNTHETIC_MONITORING = "false";
    public static final String DEFAULT_SCENARIO_NAME = "Scenario";
    public static final String DEFAULT_SAVE_RESPONSE_BODY = "true";
    public static final String DEFAULT_NORMALIZE_URLS = "true";
    public static final String DEFAULT_FLATTEN_NESTED_TRANSACTIONS = "true";
    public static final String DEFAULT_SAVE_SESSION_VARIABLES = "false";
    public static final String DEFAULT_SESSION_VARIABLES_EXCLUDE =
            "password,passwd,pwd,token,secret,authorization,auth,apikey,api_key," +
            "sessionid,jsessionid,cookie,credential,bearer";
    public static final String DEFAULT_SESSION_VARIABLES_MAX_VALUE_LENGTH = "2048";
    public static final String DEFAULT_SESSION_VARIABLES_MAX_TOTAL_BYTES = "16384";

    // Table names
    public static final String TABLE_REQUESTS_RAW = "requests_raw";
    public static final String TABLE_TRANSACTIONS = "transactions";
    public static final String TABLE_REQUESTS_ERROR = "requests_error";
    public static final String TABLE_VIRTUAL_USERS = "virtual_users";
    public static final String TABLE_URL_PATTERNS = "url_patterns";

    /**
     * Creates a TimescaleDBConfig from a BackendListenerContext.
     *
     * @param context the JMeter backend listener context
     * @return configured TimescaleDBConfig instance
     */
    public static TimescaleDBConfig fromContext(BackendListenerContext context) {
        TimescaleDBConfig config = new TimescaleDBConfig();

        // Connection settings
        config.host = context.getParameter(KEY_HOST, DEFAULT_HOST).trim();
        config.port = Integer.parseInt(context.getParameter(KEY_PORT, DEFAULT_PORT).trim());
        config.database = context.getParameter(KEY_DATABASE, DEFAULT_DATABASE).trim();
        config.schema = context.getParameter(KEY_SCHEMA, DEFAULT_SCHEMA).trim();
        config.user = context.getParameter(KEY_USER, DEFAULT_USER).trim();
        config.password = context.getParameter(KEY_PASSWORD, DEFAULT_PASSWORD).trim();

        // SSL settings
        config.sslMode = context.getParameter(KEY_SSL_MODE, DEFAULT_SSL_MODE);

        // Connection pool settings
        config.maxPoolSize = Integer.parseInt(context.getParameter(KEY_MAX_POOL_SIZE, DEFAULT_MAX_POOL_SIZE));
        config.connectionTimeout = Integer.parseInt(context.getParameter(KEY_CONNECTION_TIMEOUT, DEFAULT_CONNECTION_TIMEOUT));

        // Batch settings
        config.batchSize = Integer.parseInt(context.getParameter(KEY_BATCH_SIZE, DEFAULT_BATCH_SIZE));
        config.flushInterval = Integer.parseInt(context.getParameter(KEY_FLUSH_INTERVAL, DEFAULT_FLUSH_INTERVAL));

        // Test identification
        config.runId = context.getParameter(KEY_RUN_ID, DEFAULT_RUN_ID);
        config.location = context.getParameter(KEY_LOCATION, DEFAULT_LOCATION);
        config.nodeName = context.getParameter(KEY_NODE_NAME, DEFAULT_NODE_NAME);
        config.systemUnderTest = context.getParameter(KEY_SYSTEM_UNDER_TEST, DEFAULT_SYSTEM_UNDER_TEST);
        config.testEnvironment = context.getParameter(KEY_TEST_ENVIRONMENT, DEFAULT_TEST_ENVIRONMENT);

        // Mode settings
        config.syntheticMonitoring = Boolean.parseBoolean(context.getParameter(KEY_SYNTHETIC_MONITORING, DEFAULT_SYNTHETIC_MONITORING));
        config.scenarioName = context.getParameter(KEY_SCENARIO_NAME, DEFAULT_SCENARIO_NAME);

        // Data capture settings
        config.saveResponseBody = Boolean.parseBoolean(context.getParameter(KEY_SAVE_RESPONSE_BODY, DEFAULT_SAVE_RESPONSE_BODY));

        // URL normalization settings
        config.normalizeUrls = Boolean.parseBoolean(context.getParameter(KEY_NORMALIZE_URLS, DEFAULT_NORMALIZE_URLS));

        // Transaction flattening settings
        config.flattenNestedTransactions = Boolean.parseBoolean(context.getParameter(KEY_FLATTEN_NESTED_TRANSACTIONS, DEFAULT_FLATTEN_NESTED_TRANSACTIONS));

        // Session variable capture settings
        config.saveSessionVariables = Boolean.parseBoolean(
                context.getParameter(KEY_SAVE_SESSION_VARIABLES, DEFAULT_SAVE_SESSION_VARIABLES));
        // Fall back to the secure built-in deny-list when the argument is blank (e.g. the
        // GUI default uses ${__P(sessionVariablesExclude,)} and the property is unset). An
        // empty deny-list would silently capture secret-named variables, so blank means
        // "use the default", not "deny nothing".
        String sessionVariablesExcludeRaw =
                context.getParameter(KEY_SESSION_VARIABLES_EXCLUDE, DEFAULT_SESSION_VARIABLES_EXCLUDE);
        if (sessionVariablesExcludeRaw == null || sessionVariablesExcludeRaw.trim().isEmpty()) {
            sessionVariablesExcludeRaw = DEFAULT_SESSION_VARIABLES_EXCLUDE;
        }
        config.sessionVariablesExclude = parseDenyList(sessionVariablesExcludeRaw);
        config.sessionVariablesMaxValueLength = Integer.parseInt(
                context.getParameter(KEY_SESSION_VARIABLES_MAX_VALUE_LENGTH, DEFAULT_SESSION_VARIABLES_MAX_VALUE_LENGTH).trim());
        config.sessionVariablesMaxTotalBytes = Integer.parseInt(
                context.getParameter(KEY_SESSION_VARIABLES_MAX_TOTAL_BYTES, DEFAULT_SESSION_VARIABLES_MAX_TOTAL_BYTES).trim());

        return config;
    }

    private static Set<String> parseDenyList(String csv) {
        Set<String> set = new HashSet<>();
        if (csv == null) {
            return set;
        }
        for (String name : csv.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                set.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return set;
    }

    /**
     * Builds the JDBC URL for TimescaleDB/PostgreSQL connection.
     *
     * @return JDBC connection URL
     */
    public String getJdbcUrl() {
        return String.format("jdbc:postgresql://%s:%d/%s?sslmode=%s",
                host, port, database, sslMode);
    }

    /**
     * Gets the fully qualified table name including schema.
     *
     * @param tableName the table name
     * @return fully qualified table name (schema.table)
     */
    public String getFullTableName(String tableName) {
        return schema + "." + tableName;
    }

    /**
     * Gets the effective run ID based on the operating mode.
     * In synthetic monitoring mode, returns scenarioName; otherwise returns runId.
     *
     * @return effective run identifier
     */
    public String getEffectiveRunId() {
        return syntheticMonitoring ? scenarioName : runId;
    }

    // Getters

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDatabase() {
        return database;
    }

    public String getSchema() {
        return schema;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    public String getSslMode() {
        return sslMode;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getFlushInterval() {
        return flushInterval;
    }

    public String getRunId() {
        return runId;
    }

    public String getLocation() {
        return location;
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getSystemUnderTest() {
        return systemUnderTest;
    }

    public String getTestEnvironment() {
        return testEnvironment;
    }

    public boolean isSyntheticMonitoring() {
        return syntheticMonitoring;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public boolean isSaveResponseBody() {
        return saveResponseBody;
    }

    public boolean isNormalizeUrls() {
        return normalizeUrls;
    }

    public boolean isFlattenNestedTransactions() {
        return flattenNestedTransactions;
    }

    public boolean isSaveSessionVariables() {
        return saveSessionVariables;
    }

    public Set<String> getSessionVariablesExclude() {
        return sessionVariablesExclude;
    }

    public int getSessionVariablesMaxValueLength() {
        return sessionVariablesMaxValueLength;
    }

    public int getSessionVariablesMaxTotalBytes() {
        return sessionVariablesMaxTotalBytes;
    }
}
