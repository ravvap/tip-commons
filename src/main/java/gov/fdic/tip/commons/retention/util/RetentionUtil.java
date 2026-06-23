package gov.fdic.tip.commons.retention.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.fdic.tip.commons.retention.exception.RetentionException;
import gov.fdic.tip.commons.retention.exception.RetentionException.ErrorCode;
import gov.fdic.tip.commons.retention.model.RetentionContext;

/**
 * <h2>RetentionUtil</h2>
 *
 * <p>Static utility (no Spring required) that stamps {@code purge_date} and
 * {@code effective_date} on an operational-table row immediately after insert,
 * based on the retention configuration held in
 * {@code txn.retention_table_registry} → {@code txn.retention_sub_categories}.</p>
 *
 * <h3>Quick start — one call after your INSERT</h3>
 * <pre>{@code
 * UUID newId = yourDao.insert(conn, record);
 * try {
 *     RetentionUtil.applyRetention(conn, "txn", "tbl_jpmcinvoices", newId);
 * } catch (RetentionException e) {
 *     log.error("Retention stamp failed [{}]: {}", e.getErrorCode(), e.getMessage(), e);
 * }
 * }</pre>
 *
 * <h3>Advanced — inspect before writing</h3>
 * <pre>{@code
 * RetentionContext ctx = RetentionUtil.resolve(conn, "txn", "tbl_jpmcinvoices", newId);
 * log.info("Purge scheduled: {}", ctx.getCalculatedPurgeDate());
 * RetentionUtil.apply(conn, ctx);
 * }</pre>
 *
 * <h3>Legal hold</h3>
 * <p>When {@code legal_hold_status = TRUE} in the registry, {@code purge_date}
 * is written as {@code NULL}. {@code effective_date} is still stamped.</p>
 *
 * <h3>Transaction contract</h3>
 * <p>This utility does NOT manage transactions. Pass the same {@link Connection}
 * used for the INSERT so both statements commit or roll back together.</p>
 *
 * <h3>Thread safety</h3>
 * <p>All methods are stateless and fully thread-safe.</p>
 */
public final class RetentionUtil {

    private static final Logger log = LoggerFactory.getLogger(RetentionUtil.class);

    // -----------------------------------------------------------------------
    // SQL
    // -----------------------------------------------------------------------

    /**
     * Single-join query: registry + sub-category → avoids two round-trips.
     * Only matches rows that are active and within their effective date window.
     */
    private static final String SQL_RESOLVE_REGISTRY =
            "SELECT " +
            "    r.basis_date_column, " +
            "    r.legal_hold_status, " +
            "    s.id                       AS sub_category_id, " +
            "    s.code                     AS sub_category_code, " +
            "    s.retention_duration_value AS dur_value, " +
            "    s.retention_duration_unit  AS dur_unit " +
            "FROM  txn.retention_table_registry r " +
            "JOIN  txn.retention_sub_categories s ON s.id = r.sub_category_id " +
            "WHERE r.schema_name  = ? " +
            "  AND r.table_name   = ? " +
            "  AND r.is_active    = TRUE " +
            "  AND r.deleted_at  IS NULL " +
            "  AND NOW() BETWEEN r.rtn_effective_date AND r.rtn_end_date";

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Convenience method: resolve retention config and immediately apply the
     * UPDATE in one call.
     *
     * @param conn       active JDBC {@link Connection} (may be mid-transaction)
     * @param schemaName schema of the operational table (e.g. {@code "txn"})
     * @param tableName  operational table name (e.g. {@code "tbl_jpmcinvoices"})
     * @param rowId      {@link UUID} of the row just inserted
     * @throws RetentionException on any resolution or UPDATE failure
     */
    public static void applyRetention(
            Connection conn,
            String     schemaName,
            String     tableName,
            UUID       rowId) throws RetentionException {

        RetentionContext ctx = resolve(conn, schemaName, tableName, rowId);
        apply(conn, ctx);
    }

    /**
     * Step 1 of 2 (advanced): resolve retention configuration into a
     * {@link RetentionContext} — reads the registry and calculates dates
     * but makes <em>no</em> writes to the database.
     *
     * @param conn       active JDBC {@link Connection}
     * @param schemaName schema of the operational table
     * @param tableName  operational table name
     * @param rowId      UUID of the newly inserted row
     * @return populated {@link RetentionContext}
     * @throws RetentionException if configuration cannot be resolved
     */
    public static RetentionContext resolve(
            Connection conn,
            String     schemaName,
            String     tableName,
            UUID       rowId) throws RetentionException {

        validateArgs(conn, schemaName, tableName, rowId);

        // 1. Load registry + sub-category config
        RegistryEntry entry = fetchRegistryEntry(conn, schemaName, tableName);

        // 2. Read the basis date from the operational row
        OffsetDateTime basisDate = fetchBasisDate(
                conn, schemaName, tableName, entry.basisDateColumn, rowId);

        // 3. Calculate
        OffsetDateTime now       = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime purgeDate = entry.legalHoldActive
                ? null
                : addDuration(basisDate, entry.durationValue, entry.durationUnit);

        if (entry.legalHoldActive) {
            log.warn("Legal hold ACTIVE for {}.{} — purge_date will be NULL for row {}",
                    schemaName, tableName, rowId);
        } else {
            log.debug("Resolved retention for {}.{} row={} purgeDate={} ({}+{} {})",
                    schemaName, tableName, rowId, purgeDate,
                    basisDate, entry.durationValue, entry.durationUnit);
        }

        return new RetentionContext(
                schemaName,
                tableName,
                rowId,
                entry.subCategoryId,
                entry.subCategoryCode,
                entry.durationValue,
                entry.durationUnit,
                entry.legalHoldActive,
                entry.basisDateColumn,
                basisDate,
                purgeDate,
                now);
    }

    /**
     * Step 2 of 2 (advanced): write the calculated {@code purge_date} and
     * {@code effective_date} values from a {@link RetentionContext} back to
     * the operational table row.
     *
     * @param conn active JDBC {@link Connection}
     * @param ctx  context produced by {@link #resolve}
     * @throws RetentionException if the UPDATE fails or the row is gone
     */
    public static void apply(Connection conn, RetentionContext ctx)
            throws RetentionException {

        if (ctx == null) {
            throw new RetentionException(
                    ErrorCode.INVALID_ARGUMENT, "RetentionContext must not be null");
        }

        // Identifiers come from registry data (trusted) but are still sanitised
        String safeSchema = sanitiseIdentifier(ctx.getSchemaName());
        String safeTable  = sanitiseIdentifier(ctx.getTableName());

        String sql = String.format(
                "UPDATE %s.%s " +
                "SET    purge_date     = ?, " +
                "       effective_date = ? " +
                "WHERE  id = ?",
                safeSchema, safeTable);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {

            if (ctx.getCalculatedPurgeDate() != null) {
                ps.setObject(1, ctx.getCalculatedPurgeDate());
            } else {
                ps.setNull(1, Types.TIMESTAMP_WITH_TIMEZONE);
            }
            ps.setObject(2, ctx.getEffectiveDate());
            ps.setObject(3, ctx.getRowId());

            int affected = ps.executeUpdate();

            if (affected == 0) {
                throw new RetentionException(
                        ErrorCode.ROW_NOT_FOUND,
                        String.format(
                            "UPDATE matched 0 rows — id=%s not found in %s.%s. " +
                            "Ensure the row is visible in this transaction before calling apply().",
                            ctx.getRowId(), ctx.getSchemaName(), ctx.getTableName()));
            }

            log.info("Retention applied → {}.{} id={} | purge_date={} | effective_date={}",
                    ctx.getSchemaName(), ctx.getTableName(), ctx.getRowId(),
                    ctx.getCalculatedPurgeDate(), ctx.getEffectiveDate());

        } catch (SQLException e) {
            throw new RetentionException(
                    ErrorCode.SQL_ERROR,
                    String.format("UPDATE failed for %s.%s id=%s: %s",
                            ctx.getSchemaName(), ctx.getTableName(), ctx.getRowId(),
                            e.getMessage()),
                    e);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static RegistryEntry fetchRegistryEntry(
            Connection conn, String schemaName, String tableName)
            throws RetentionException {

        try (PreparedStatement ps = conn.prepareStatement(SQL_RESOLVE_REGISTRY)) {
            ps.setString(1, schemaName);
            ps.setString(2, tableName);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new RetentionException(
                            ErrorCode.REGISTRY_NOT_FOUND,
                            String.format(
                                "No active retention_table_registry entry found for '%s.%s'. " +
                                "Ensure the table has been onboarded and is within its " +
                                "rtn_effective_date / rtn_end_date window.",
                                schemaName, tableName));
                }

                RegistryEntry e     = new RegistryEntry();
                e.basisDateColumn   = rs.getString("basis_date_column");
                e.legalHoldActive   = rs.getBoolean("legal_hold_status");
                e.subCategoryId     = UUID.fromString(rs.getString("sub_category_id"));
                e.subCategoryCode   = rs.getString("sub_category_code");
                e.durationValue     = rs.getInt("dur_value");
                e.durationUnit      = rs.getString("dur_unit").toLowerCase();
                return e;
            }

        } catch (SQLException e) {
            throw new RetentionException(
                    ErrorCode.SQL_ERROR,
                    "Failed to query retention_table_registry: " + e.getMessage(), e);
        }
    }

    private static OffsetDateTime fetchBasisDate(
            Connection conn,
            String     schemaName,
            String     tableName,
            String     basisDateColumn,
            UUID       rowId) throws RetentionException {

        String safeSchema = sanitiseIdentifier(schemaName);
        String safeTable  = sanitiseIdentifier(tableName);
        String safeCol    = sanitiseIdentifier(basisDateColumn);

        String sql = String.format(
                "SELECT %s FROM %s.%s WHERE id = ?",
                safeCol, safeSchema, safeTable);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, rowId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new RetentionException(
                            ErrorCode.ROW_NOT_FOUND,
                            String.format("Row id=%s not found in %s.%s when reading basis date '%s'.",
                                    rowId, schemaName, tableName, basisDateColumn));
                }

                Timestamp ts = rs.getTimestamp(1);
                if (ts == null) {
                    throw new RetentionException(
                            ErrorCode.BASIS_DATE_NULL,
                            String.format(
                                "Basis date column '%s' is NULL for row id=%s in %s.%s. " +
                                "purge_date cannot be calculated without a basis date.",
                                basisDateColumn, rowId, schemaName, tableName));
                }

                return ts.toInstant().atOffset(ZoneOffset.UTC);
            }

        } catch (SQLException e) {
            throw new RetentionException(
                    ErrorCode.SQL_ERROR,
                    String.format("Failed to read basis date column '%s' from %s.%s for row %s: %s",
                            basisDateColumn, schemaName, tableName, rowId, e.getMessage()),
                    e);
        }
    }

    /**
     * Adds the retention duration to the basis date using exact calendar
     * arithmetic. Maps directly to the values stored in
     * {@code retention_sub_categories.retention_duration_unit}.
     */
    static OffsetDateTime addDuration(OffsetDateTime basis, int value, String unit) {
        return switch (unit) {
            case "days"   -> basis.plusDays(value);
            case "months" -> basis.plusMonths(value);
            case "years"  -> basis.plusYears(value);
            default -> throw new IllegalStateException(
                    "Unknown retention_duration_unit: '" + unit +
                    "'. Must be one of: days, months, years.");
        };
    }

    /**
     * Guards against SQL injection when interpolating schema / table / column
     * identifiers into dynamic SQL strings.
     *
     * <p>Allows only {@code [a-zA-Z_][a-zA-Z0-9_]*} — sufficient for all
     * standard PostgreSQL identifier naming conventions used in TIP.</p>
     */
    static String sanitiseIdentifier(String identifier) throws RetentionException {
        if (identifier == null || identifier.isBlank()) {
            throw new RetentionException(
                    ErrorCode.INVALID_ARGUMENT,
                    "SQL identifier must not be null or blank.");
        }
        if (!identifier.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new RetentionException(
                    ErrorCode.INVALID_ARGUMENT,
                    "SQL identifier contains disallowed characters: '" + identifier + "'. " +
                    "Only letters, digits, and underscores are permitted.");
        }
        return identifier;
    }

    private static void validateArgs(
            Connection conn, String schemaName, String tableName, UUID rowId)
            throws RetentionException {

        if (conn == null) {
            throw new RetentionException(
                    ErrorCode.INVALID_ARGUMENT, "Connection must not be null.");
        }
        if (schemaName == null || schemaName.isBlank()) {
            throw new RetentionException(
                    ErrorCode.INVALID_ARGUMENT, "schemaName must not be null or blank.");
        }
        if (tableName == null || tableName.isBlank()) {
            throw new RetentionException(
                    ErrorCode.INVALID_ARGUMENT, "tableName must not be null or blank.");
        }
        if (rowId == null) {
            throw new RetentionException(
                    ErrorCode.INVALID_ARGUMENT, "rowId must not be null.");
        }
    }

    /** Internal data-transfer struct for registry query results. */
    private static final class RegistryEntry {
        String  basisDateColumn;
        boolean legalHoldActive;
        UUID    subCategoryId;
        String  subCategoryCode;
        int     durationValue;
        String  durationUnit;
    }

    // Prevent instantiation
    private RetentionUtil() {}
}
