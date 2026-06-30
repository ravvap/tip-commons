package com.fdic.tip.commons.retention.core;

import com.fdic.tip.commons.retention.constants.RetentionConstants;
import com.fdic.tip.commons.retention.constants.RetentionConstants.DurationUnit;
import com.fdic.tip.commons.retention.constants.RetentionConstants.IdentifierPattern;
import com.fdic.tip.commons.retention.constants.RetentionConstants.OperationalTable;
import com.fdic.tip.commons.retention.constants.RetentionConstants.ResultSetAlias;
import com.fdic.tip.commons.retention.constants.RetentionMessages;
import com.fdic.tip.commons.retention.constants.RetentionSql;
import com.fdic.tip.commons.retention.exception.RetentionException;
import com.fdic.tip.commons.retention.exception.RetentionException.ErrorCode;
import com.fdic.tip.commons.retention.model.RetentionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Core retention processing logic — shared by both
 * {@link com.fdic.tip.commons.retention.service.RetentionService} (Spring bean)
 * and {@link com.fdic.tip.commons.retention.service.RetentionUtil} (static fallback).
 *
 * <p>This class is intentionally <em>not</em> a Spring component.  It is a plain
 * Java class that works with a {@link DataSource} directly, so neither the
 * Spring nor the non-Spring path duplicates any logic.</p>
 *
 * <h3>Responsibility</h3>
 * <ol>
 *   <li>Validate arguments.</li>
 *   <li>Look up {@code retention_table_registry} + {@code retention_sub_categories}.</li>
 *   <li>Read the basis date from the operational row.</li>
 *   <li>Calculate {@code purge_date} and {@code effective_date}.</li>
 *   <li>Execute the UPDATE on the operational row.</li>
 * </ol>
 */
public final class RetentionEngine {

    private static final Logger log = LoggerFactory.getLogger(RetentionEngine.class);

    private final DataSource dataSource;

    public RetentionEngine(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Resolve + apply in one call.
     *
     * @param schemaName schema of the operational table (e.g. {@code "txn"})
     * @param tableName  operational table name (e.g. {@code "tbl_jpmcinvoices"})
     * @param rowId      UUID of the row just inserted
     * @throws RetentionException on any resolution or UPDATE failure
     */
    public void applyRetention(String schemaName, String tableName, UUID rowId)
            throws RetentionException {

        RetentionContext ctx = resolve(schemaName, tableName, rowId);
        apply(ctx);
    }

    /**
     * Step 1: resolve retention configuration into a {@link RetentionContext}
     * without writing anything to the database.
     *
     * @param schemaName schema of the operational table
     * @param tableName  operational table name
     * @param rowId      UUID of the newly inserted row
     * @return fully-populated {@link RetentionContext}
     * @throws RetentionException if configuration cannot be resolved
     */
    public RetentionContext resolve(String schemaName, String tableName, UUID rowId)
            throws RetentionException {

        validateArgs(schemaName, tableName, rowId);

        try (Connection conn = dataSource.getConnection()) {
            RegistryEntry entry    = fetchRegistryEntry(conn, schemaName, tableName);
            OffsetDateTime basis   = fetchBasisDate(conn, schemaName, tableName,
                                                    entry.basisDateColumn, rowId);
            OffsetDateTime now     = OffsetDateTime.now(ZoneOffset.UTC);
            OffsetDateTime purgeDate = computePurgeDate(entry, basis, schemaName, tableName, rowId);

            log.debug(RetentionMessages.LOG_RETENTION_RESOLVED,
                    schemaName, tableName, rowId, purgeDate,
                    basis, entry.durationValue, entry.durationUnit);

            return new RetentionContext(
                    schemaName, tableName, rowId,
                    entry.subCategoryId, entry.subCategoryCode,
                    entry.durationValue, entry.durationUnit,
                    entry.legalHoldActive,
                    entry.basisDateColumn, basis,
                    purgeDate, now);

        } catch (SQLException e) {
            throw new RetentionException(ErrorCode.SQL_ERROR,
                    String.format(RetentionMessages.ERR_SQL_REGISTRY_QUERY_FAILED,
                            e.getMessage()), e);
        }
    }

    /**
     * Step 2: write the calculated {@code purge_date} and {@code effective_date}
     * from a {@link RetentionContext} to the operational table row.
     *
     * @param ctx context produced by {@link #resolve}
     * @throws RetentionException if the UPDATE fails
     */
    public void apply(RetentionContext ctx) throws RetentionException {
        if (ctx == null) {
            throw new RetentionException(ErrorCode.INVALID_ARGUMENT,
                    RetentionMessages.ERR_NULL_CONTEXT);
        }

        String safeSchema = sanitiseIdentifier(ctx.getSchemaName());
        String safeTable  = sanitiseIdentifier(ctx.getTableName());
        String sql = String.format(RetentionSql.STAMP_RETENTION_TEMPLATE, safeSchema, safeTable);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            if (ctx.getCalculatedPurgeDate() != null) {
                ps.setObject(1, ctx.getCalculatedPurgeDate());
            } else {
                ps.setNull(1, Types.TIMESTAMP_WITH_TIMEZONE);
            }
            ps.setObject(2, ctx.getEffectiveDate());
            ps.setObject(3, ctx.getRowId());

            int affected = ps.executeUpdate();
            if (affected == 0) {
                throw new RetentionException(ErrorCode.ROW_NOT_FOUND,
                        String.format(RetentionMessages.ERR_ROW_NOT_FOUND_UPDATE,
                                ctx.getRowId(), ctx.getSchemaName(), ctx.getTableName()));
            }

            log.info(RetentionMessages.LOG_RETENTION_APPLIED,
                    ctx.getSchemaName(), ctx.getTableName(), ctx.getRowId(),
                    ctx.getCalculatedPurgeDate(), ctx.getEffectiveDate());

        } catch (SQLException e) {
            throw new RetentionException(ErrorCode.SQL_ERROR,
                    String.format(RetentionMessages.ERR_SQL_STAMP_FAILED,
                            ctx.getSchemaName(), ctx.getTableName(),
                            ctx.getRowId(), e.getMessage()), e);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private RegistryEntry fetchRegistryEntry(Connection conn,
                                             String schemaName,
                                             String tableName) throws RetentionException {
        try (PreparedStatement ps = conn.prepareStatement(
                RetentionSql.RESOLVE_REGISTRY_AND_SUB_CATEGORY)) {

            ps.setString(1, schemaName);
            ps.setString(2, tableName);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new RetentionException(ErrorCode.REGISTRY_NOT_FOUND,
                            String.format(RetentionMessages.ERR_REGISTRY_NOT_FOUND,
                                    schemaName, tableName));
                }

                RegistryEntry e    = new RegistryEntry();
                e.basisDateColumn  = rs.getString(RetentionConstants.RegistryTable.BASIS_DATE_COLUMN);
                e.legalHoldActive  = rs.getBoolean(RetentionConstants.RegistryTable.LEGAL_HOLD_STATUS);
                e.subCategoryId    = UUID.fromString(rs.getString(ResultSetAlias.SUB_CATEGORY_ID));
                e.subCategoryCode  = rs.getString(ResultSetAlias.SUB_CATEGORY_CODE);
                e.durationValue    = rs.getInt(ResultSetAlias.DUR_VALUE);
                e.durationUnit     = rs.getString(ResultSetAlias.DUR_UNIT).toLowerCase();
                return e;
            }

        } catch (SQLException e) {
            throw new RetentionException(ErrorCode.SQL_ERROR,
                    String.format(RetentionMessages.ERR_SQL_REGISTRY_QUERY_FAILED,
                            e.getMessage()), e);
        }
    }

    private OffsetDateTime fetchBasisDate(Connection conn,
                                          String schemaName,
                                          String tableName,
                                          String basisDateColumn,
                                          UUID rowId) throws RetentionException {

        String safeSchema = sanitiseIdentifier(schemaName);
        String safeTable  = sanitiseIdentifier(tableName);
        String safeCol    = sanitiseIdentifier(basisDateColumn);
        String sql        = String.format(RetentionSql.READ_BASIS_DATE_TEMPLATE,
                                          safeCol, safeSchema, safeTable);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, rowId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new RetentionException(ErrorCode.ROW_NOT_FOUND,
                            String.format(RetentionMessages.ERR_ROW_NOT_FOUND_BASIS,
                                    rowId, schemaName, tableName, basisDateColumn));
                }
                Timestamp ts = rs.getTimestamp(1);
                if (ts == null) {
                    throw new RetentionException(ErrorCode.BASIS_DATE_NULL,
                            String.format(RetentionMessages.ERR_BASIS_DATE_NULL,
                                    basisDateColumn, rowId, schemaName, tableName));
                }
                return ts.toInstant().atOffset(ZoneOffset.UTC);
            }

        } catch (SQLException e) {
            throw new RetentionException(ErrorCode.SQL_ERROR,
                    String.format(RetentionMessages.ERR_SQL_BASIS_DATE_FAILED,
                            basisDateColumn, schemaName, tableName, rowId, e.getMessage()), e);
        }
    }

    private OffsetDateTime computePurgeDate(RegistryEntry entry,
                                            OffsetDateTime basis,
                                            String schemaName,
                                            String tableName,
                                            UUID rowId) {
        if (entry.legalHoldActive) {
            log.warn(RetentionMessages.WARN_LEGAL_HOLD_ACTIVE, schemaName, tableName, rowId);
            return null;
        }
        return addDuration(basis, entry.durationValue, entry.durationUnit);
    }

    static OffsetDateTime addDuration(OffsetDateTime basis, int value, String unit) {
        return switch (unit) {
            case DurationUnit.DAYS   -> basis.plusDays(value);
            case DurationUnit.MONTHS -> basis.plusMonths(value);
            case DurationUnit.YEARS  -> basis.plusYears(value);
            default -> throw new IllegalStateException(
                    String.format(RetentionMessages.ERR_UNKNOWN_DURATION_UNIT, unit));
        };
    }

    static String sanitiseIdentifier(String identifier) throws RetentionException {
        if (identifier == null || identifier.isBlank()) {
            throw new RetentionException(ErrorCode.INVALID_ARGUMENT,
                    RetentionMessages.ERR_BLANK_IDENTIFIER);
        }
        if (!identifier.matches(IdentifierPattern.VALID_IDENTIFIER)) {
            throw new RetentionException(ErrorCode.INVALID_ARGUMENT,
                    String.format(RetentionMessages.ERR_INVALID_IDENTIFIER, identifier));
        }
        return identifier;
    }

    private void validateArgs(String schemaName, String tableName, UUID rowId)
            throws RetentionException {
        if (schemaName == null || schemaName.isBlank()) {
            throw new RetentionException(ErrorCode.INVALID_ARGUMENT,
                    RetentionMessages.ERR_NULL_SCHEMA);
        }
        if (tableName == null || tableName.isBlank()) {
            throw new RetentionException(ErrorCode.INVALID_ARGUMENT,
                    RetentionMessages.ERR_NULL_TABLE);
        }
        if (rowId == null) {
            throw new RetentionException(ErrorCode.INVALID_ARGUMENT,
                    RetentionMessages.ERR_NULL_ROW_ID);
        }
    }

    /** Internal data struct for registry + sub-category query results. */
    static final class RegistryEntry {
        String  basisDateColumn;
        boolean legalHoldActive;
        UUID    subCategoryId;
        String  subCategoryCode;
        int     durationValue;
        String  durationUnit;
    }
}
