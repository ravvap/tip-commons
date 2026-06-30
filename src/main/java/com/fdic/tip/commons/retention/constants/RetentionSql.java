package com.fdic.tip.commons.retention.constants;

/**
 * All SQL statements used by the retention module.
 *
 * <p><strong>Rule:</strong> no SQL string literals anywhere else in the retention
 * package — every query lives here so the team has one place to review,
 * tune, or swap statements.</p>
 *
 * <p>Parameterisation uses JDBC {@code ?} positional placeholders throughout.
 * Identifier tokens (schema, table, column) are injected via
 * {@link String#format} <em>after</em> passing through
 * {@code RetentionIdentifierSanitiser} — never concatenated raw.</p>
 *
 * <p>Future modules (Aspose, Audit, …) add their own {@code XxxSql} class
 * under their own {@code constants} package — they do <em>not</em> go here.</p>
 */
public final class RetentionSql {

    // -----------------------------------------------------------------------
    // Registry + sub-category resolution
    // Single join so we pay one round-trip instead of two.
    // Only matches registry rows that are:
    //   - is_active = TRUE
    //   - not soft-deleted (deleted_at IS NULL)
    //   - currently within their effective date window
    // -----------------------------------------------------------------------

    /**
     * Resolves the full retention configuration for a given schema + table.
     *
     * <p>Bind order: {@code (1) schema_name, (2) table_name}</p>
     */
    public static final String RESOLVE_REGISTRY_AND_SUB_CATEGORY =
            "SELECT " +
            "    r.basis_date_column, " +
            "    r.legal_hold_status, " +
            "    s.id                       AS sub_category_id, " +
            "    s.code                     AS sub_category_code, " +
            "    s.retention_duration_value AS dur_value, " +
            "    s.retention_duration_unit  AS dur_unit  " +
            "FROM  txn.retention_table_registry   r " +
            "JOIN  txn.retention_sub_categories   s ON s.id = r.sub_category_id " +
            "WHERE r.schema_name   = ?  " +
            "  AND r.table_name    = ?  " +
            "  AND r.is_active     = TRUE " +
            "  AND r.deleted_at   IS NULL " +
            "  AND NOW() BETWEEN r.rtn_effective_date AND r.rtn_end_date";

    // -----------------------------------------------------------------------
    // Basis date read
    // Schema, table, and column are identifier-safe tokens injected via
    // String.format — positional bind (1) is the row UUID.
    // Template: SELECT <col> FROM <schema>.<table> WHERE id = ?
    // -----------------------------------------------------------------------

    /**
     * Template for reading the configured basis-date column from an
     * operational row.  Use {@link String#format} to substitute
     * {@code %s} tokens <em>only after sanitising identifiers</em>.
     *
     * <p>Bind order after formatting: {@code (1) row UUID}</p>
     *
     * <pre>{@code
     * String sql = String.format(RetentionSql.READ_BASIS_DATE_TEMPLATE,
     *                            safeColumn, safeSchema, safeTable);
     * }</pre>
     */
    public static final String READ_BASIS_DATE_TEMPLATE =
            "SELECT %s FROM %s.%s WHERE id = ?";

    // -----------------------------------------------------------------------
    // Retention stamp UPDATE
    // Schema and table are identifier-safe tokens injected via String.format.
    // Template: UPDATE <schema>.<table> SET purge_date=?, effective_date=? WHERE id=?
    // -----------------------------------------------------------------------

    /**
     * Template for stamping {@code purge_date} and {@code effective_date}
     * onto the operational row after insert.
     * Use {@link String#format} to substitute {@code %s} tokens
     * <em>only after sanitising identifiers</em>.
     *
     * <p>Bind order after formatting:
     * {@code (1) purge_date, (2) effective_date, (3) row UUID}</p>
     *
     * <pre>{@code
     * String sql = String.format(RetentionSql.STAMP_RETENTION_TEMPLATE,
     *                            safeSchema, safeTable);
     * }</pre>
     */
    public static final String STAMP_RETENTION_TEMPLATE =
            "UPDATE %s.%s " +
            "SET    purge_date     = ?, " +
            "       effective_date = ? " +
            "WHERE  id = ?";

    private RetentionSql() {}
}
