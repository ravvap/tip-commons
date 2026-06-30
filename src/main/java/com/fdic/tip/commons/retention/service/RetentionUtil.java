package com.fdic.tip.commons.retention.service;

import com.fdic.tip.commons.retention.core.RetentionEngine;
import com.fdic.tip.commons.retention.exception.RetentionException;
import com.fdic.tip.commons.retention.exception.RetentionException.ErrorCode;
import com.fdic.tip.commons.retention.model.RetentionContext;
import com.fdic.tip.commons.retention.constants.RetentionMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * <h2>RetentionUtil — static API for non-Spring projects</h2>
 *
 * <p>Provides the same retention-stamping capability as {@link RetentionService}
 * via static methods, for teams that do not use the Spring IoC container.</p>
 *
 * <h3>Usage — with explicit DataSource per call</h3>
 * <pre>{@code
 * RetentionUtil.applyRetention(dataSource, "txn", "tbl_jpmcinvoices", newRowId);
 * }</pre>
 *
 * <h3>Usage — register a global DataSource once at startup</h3>
 * <pre>{@code
 * // In your application bootstrap (e.g. main() or a startup listener):
 * RetentionUtil.registerDataSource(dataSource);
 *
 * // Then anywhere in the application:
 * RetentionUtil.applyRetention("txn", "tbl_jpmcinvoices", newRowId);
 * }</pre>
 *
 * <h3>Spring / Spring Boot teams</h3>
 * <p>Prefer {@link RetentionService} — it is injected by Spring and requires
 * no static setup.  {@code RetentionUtil} is not needed in Spring projects.</p>
 *
 * <h3>Thread safety</h3>
 * <p>All methods are thread-safe.  The static {@code DataSource} reference is
 * set once at startup and then read-only.</p>
 */
public final class RetentionUtil {

    private static final Logger log = LoggerFactory.getLogger(RetentionUtil.class);

    /**
     * Optional global DataSource — set once via {@link #registerDataSource}.
     * Volatile ensures visibility across threads without full synchronisation.
     */
    private static volatile DataSource globalDataSource;

    // -----------------------------------------------------------------------
    // DataSource registration
    // -----------------------------------------------------------------------

    /**
     * Registers a global {@link DataSource} for use by the no-argument
     * static methods.  Call once at application startup.
     *
     * <p>In Spring Boot applications, {@code TipCommonsAutoConfiguration}
     * calls this automatically — manual registration is not needed.</p>
     *
     * @param dataSource application DataSource; must not be null
     */
    public static void registerDataSource(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException(RetentionMessages.ERR_NULL_DATASOURCE);
        }
        globalDataSource = dataSource;
        log.info(RetentionMessages.LOG_STATIC_DATASOURCE_REGISTERED,
                dataSource.getClass().getSimpleName());
    }

    // -----------------------------------------------------------------------
    // No-arg overloads — require registerDataSource() to have been called
    // -----------------------------------------------------------------------

    /**
     * Stamps retention fields using the globally registered {@link DataSource}.
     *
     * @param schemaName schema of the operational table (e.g. {@code "txn"})
     * @param tableName  operational table name (e.g. {@code "tbl_jpmcinvoices"})
     * @param rowId      UUID of the row just inserted
     * @throws RetentionException    on any resolution or UPDATE failure
     * @throws IllegalStateException if {@link #registerDataSource} was not called
     */
    public static void applyRetention(String schemaName, String tableName, UUID rowId)
            throws RetentionException {
        engine().applyRetention(schemaName, tableName, rowId);
    }

    /**
     * Resolves retention config using the globally registered {@link DataSource}.
     *
     * @param schemaName schema of the operational table
     * @param tableName  operational table name
     * @param rowId      UUID of the newly inserted row
     * @return populated {@link RetentionContext}
     * @throws RetentionException    if configuration cannot be resolved
     * @throws IllegalStateException if {@link #registerDataSource} was not called
     */
    public static RetentionContext resolve(String schemaName, String tableName, UUID rowId)
            throws RetentionException {
        return engine().resolve(schemaName, tableName, rowId);
    }

    /**
     * Writes retention dates using the globally registered {@link DataSource}.
     *
     * @param ctx context produced by {@link #resolve(String, String, UUID)}
     * @throws RetentionException    if the UPDATE fails
     * @throws IllegalStateException if {@link #registerDataSource} was not called
     */
    public static void apply(RetentionContext ctx) throws RetentionException {
        engine().apply(ctx);
    }

    // -----------------------------------------------------------------------
    // Explicit DataSource overloads — no global registration required
    // -----------------------------------------------------------------------

    /**
     * Stamps retention fields using an explicitly supplied {@link DataSource}.
     * No global registration needed — useful for per-call or multi-datasource setups.
     *
     * @param dataSource DataSource to use for this call
     * @param schemaName schema of the operational table (e.g. {@code "txn"})
     * @param tableName  operational table name (e.g. {@code "tbl_jpmcinvoices"})
     * @param rowId      UUID of the row just inserted
     * @throws RetentionException on any resolution or UPDATE failure
     */
    public static void applyRetention(DataSource dataSource,
                                      String schemaName,
                                      String tableName,
                                      UUID rowId) throws RetentionException {
        engine(dataSource).applyRetention(schemaName, tableName, rowId);
    }

    /**
     * Resolves retention config using an explicitly supplied {@link DataSource}.
     */
    public static RetentionContext resolve(DataSource dataSource,
                                           String schemaName,
                                           String tableName,
                                           UUID rowId) throws RetentionException {
        return engine(dataSource).resolve(schemaName, tableName, rowId);
    }

    /**
     * Writes retention dates using an explicitly supplied {@link DataSource}.
     */
    public static void apply(DataSource dataSource, RetentionContext ctx)
            throws RetentionException {
        engine(dataSource).apply(ctx);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /** Returns an engine backed by the globally registered DataSource. */
    private static RetentionEngine engine() throws RetentionException {
        if (globalDataSource == null) {
            throw new RetentionException(ErrorCode.INVALID_ARGUMENT,
                    "No DataSource registered. " +
                    "Call RetentionUtil.registerDataSource(ds) at application startup, " +
                    "or use the overloads that accept an explicit DataSource.");
        }
        return new RetentionEngine(globalDataSource);
    }

    /** Returns an engine backed by an explicitly supplied DataSource. */
    private static RetentionEngine engine(DataSource ds) throws RetentionException {
        if (ds == null) {
            throw new RetentionException(ErrorCode.INVALID_ARGUMENT,
                    RetentionMessages.ERR_NULL_DATASOURCE);
        }
        return new RetentionEngine(ds);
    }

    private RetentionUtil() {}
}
