package com.fdic.tip.commons.retention.constants;

/**
 * All user-facing and log messages for the retention module.
 *
 * <p><strong>Rule:</strong> no message string literals anywhere in retention
 * logic classes.  Every message lives here so wording changes, translations,
 * or ticket-number prefixes require edits in exactly one file.</p>
 *
 * <p>Messages that require runtime values use {@link String#format}-style
 * {@code %s} / {@code %d} tokens.  SLF4J log messages use {@code {}} tokens.</p>
 *
 * <h3>Naming convention</h3>
 * <ul>
 *   <li>{@code ERR_*}  — exception / error messages (thrown in RetentionException)</li>
 *   <li>{@code LOG_*}  — log messages (debug / info / warn)</li>
 *   <li>{@code WARN_*} — warning-level log messages</li>
 * </ul>
 */
public final class RetentionMessages {

    // -----------------------------------------------------------------------
    // Argument validation errors  (ErrorCode.INVALID_ARGUMENT)
    // -----------------------------------------------------------------------

    public static final String ERR_NULL_DATASOURCE =
            "DataSource must not be null.";

    public static final String ERR_NULL_SCHEMA =
            "schemaName must not be null or blank.";

    public static final String ERR_NULL_TABLE =
            "tableName must not be null or blank.";

    public static final String ERR_NULL_ROW_ID =
            "rowId must not be null.";

    public static final String ERR_NULL_CONTEXT =
            "RetentionContext must not be null.";

    /** Format args: identifier value. */
    public static final String ERR_INVALID_IDENTIFIER =
            "SQL identifier contains disallowed characters: '%s'. " +
            "Only letters, digits, and underscores are permitted.";

    public static final String ERR_BLANK_IDENTIFIER =
            "SQL identifier must not be null or blank.";

    // -----------------------------------------------------------------------
    // Registry lookup errors  (ErrorCode.REGISTRY_NOT_FOUND)
    // -----------------------------------------------------------------------

    /**
     * Format args: (1) schema_name, (2) table_name.
     */
    public static final String ERR_REGISTRY_NOT_FOUND =
            "No active retention_table_registry entry found for '%s.%s'. " +
            "Ensure the table has been onboarded and is within its " +
            "rtn_effective_date / rtn_end_date window.";

    // -----------------------------------------------------------------------
    // Sub-category errors  (ErrorCode.SUB_CATEGORY_NOT_FOUND)
    // -----------------------------------------------------------------------

    /**
     * Format args: (1) sub_category_id UUID, (2) schema_name, (3) table_name.
     */
    public static final String ERR_SUB_CATEGORY_NOT_FOUND =
            "Sub-category id=%s referenced by registry entry for '%s.%s' does not exist. " +
            "This is a data-integrity issue — contact the retention admin team.";

    // -----------------------------------------------------------------------
    // Basis date errors  (ErrorCode.BASIS_DATE_NULL / ROW_NOT_FOUND)
    // -----------------------------------------------------------------------

    /**
     * Format args: (1) basis_date_column, (2) row UUID, (3) schema, (4) table.
     */
    public static final String ERR_BASIS_DATE_NULL =
            "Basis date column '%s' is NULL for row id=%s in '%s.%s'. " +
            "Cannot calculate purge_date without a basis date. " +
            "Ensure the column is populated before calling RetentionService.";

    /**
     * Format args: (1) row UUID, (2) schema, (3) table, (4) basis_date_column.
     */
    public static final String ERR_ROW_NOT_FOUND_BASIS =
            "Row id=%s not found in '%s.%s' when reading basis date column '%s'. " +
            "Ensure the INSERT is visible in the current transaction before calling RetentionService.";

    // -----------------------------------------------------------------------
    // UPDATE errors  (ErrorCode.ROW_NOT_FOUND / SQL_ERROR)
    // -----------------------------------------------------------------------

    /**
     * Format args: (1) row UUID, (2) schema, (3) table.
     */
    public static final String ERR_ROW_NOT_FOUND_UPDATE =
            "UPDATE matched 0 rows — id=%s not found in '%s.%s'. " +
            "Ensure the row is visible in the current transaction before calling RetentionService.";

    /**
     * Format args: (1) schema, (2) table, (3) row UUID, (4) SQL exception message.
     */
    public static final String ERR_SQL_STAMP_FAILED =
            "Failed to stamp retention fields on '%s.%s' for row id=%s: %s";

    /**
     * Format args: SQL exception message.
     */
    public static final String ERR_SQL_REGISTRY_QUERY_FAILED =
            "Failed to query retention_table_registry: %s";

    /**
     * Format args: (1) basis_date_column, (2) schema, (3) table, (4) row UUID, (5) SQL exception message.
     */
    public static final String ERR_SQL_BASIS_DATE_FAILED =
            "Failed to read basis date column '%s' from '%s.%s' for row id=%s: %s";

    // -----------------------------------------------------------------------
    // Unknown duration unit  (IllegalStateException — programmer error)
    // -----------------------------------------------------------------------

    /**
     * Format args: unit string value.
     */
    public static final String ERR_UNKNOWN_DURATION_UNIT =
            "Unknown retention_duration_unit: '%s'. " +
            "Must be one of: days, months, years. " +
            "Check the value in txn.retention_sub_categories.";

    // -----------------------------------------------------------------------
    // Log messages  (SLF4J {} placeholders)
    // -----------------------------------------------------------------------

    /** Args: schema, table, rowId, purgeDate, basisDate, durationValue, durationUnit. */
    public static final String LOG_RETENTION_RESOLVED =
            "Retention resolved for {}.{} row={} | purge_date={} ({}+{} {})";

    /** Args: schema, table, rowId, purgeDate, effectiveDate. */
    public static final String LOG_RETENTION_APPLIED =
            "Retention applied → {}.{} id={} | purge_date={} | effective_date={}";

    /** Args: schema, table, rowId. */
    public static final String WARN_LEGAL_HOLD_ACTIVE =
            "Legal hold ACTIVE for {}.{} — purge_date will be set to NULL for row {}";

    /** Args: datasource class name. */
    public static final String LOG_STATIC_DATASOURCE_REGISTERED =
            "RetentionUtil static DataSource registered from: {}";

    private RetentionMessages() {}
}
