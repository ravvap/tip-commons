package com.fdic.tip.commons.retention.constants;

/**
 * Domain constants for the retention module: column names, schema names,
 * duration unit values, and result-set column aliases.
 *
 * <p><strong>Rule:</strong> if a string is used in more than one place
 * within the retention module, it lives here — not in the classes that use it.</p>
 *
 * <h3>Nested classes</h3>
 * <ul>
 *   <li>{@link Schema}        — PostgreSQL schema names</li>
 *   <li>{@link RegistryTable} — {@code retention_table_registry} column names</li>
 *   <li>{@link SubCategoryTable} — {@code retention_sub_categories} column names</li>
 *   <li>{@link OperationalTable} — columns the utility writes to operational tables</li>
 *   <li>{@link ResultSetAlias}   — column aliases used in the join query</li>
 *   <li>{@link DurationUnit}     — valid retention duration unit values</li>
 *   <li>{@link IdentifierPattern} — regex for SQL identifier sanitisation</li>
 * </ul>
 */
public final class RetentionConstants {

    // -----------------------------------------------------------------------
    // Schema
    // -----------------------------------------------------------------------

    public static final class Schema {
        /** Default schema for all TIP retention tables. */
        public static final String TXN = "txn";

        private Schema() {}
    }

    // -----------------------------------------------------------------------
    // retention_table_registry columns
    // -----------------------------------------------------------------------

    public static final class RegistryTable {
        public static final String SCHEMA_NAME        = "schema_name";
        public static final String TABLE_NAME         = "table_name";
        public static final String BASIS_DATE_COLUMN  = "basis_date_column";
        public static final String LEGAL_HOLD_STATUS  = "legal_hold_status";
        public static final String IS_ACTIVE          = "is_active";
        public static final String DELETED_AT         = "deleted_at";
        public static final String RTN_EFFECTIVE_DATE = "rtn_effective_date";
        public static final String RTN_END_DATE       = "rtn_end_date";
        public static final String SUB_CATEGORY_ID    = "sub_category_id";

        private RegistryTable() {}
    }

    // -----------------------------------------------------------------------
    // retention_sub_categories columns
    // -----------------------------------------------------------------------

    public static final class SubCategoryTable {
        public static final String ID                      = "id";
        public static final String CODE                    = "code";
        public static final String RETENTION_DURATION_VALUE = "retention_duration_value";
        public static final String RETENTION_DURATION_UNIT  = "retention_duration_unit";

        private SubCategoryTable() {}
    }

    // -----------------------------------------------------------------------
    // Operational table columns written by RetentionService
    // -----------------------------------------------------------------------

    public static final class OperationalTable {
        /** Written with the calculated purge date (basis + duration). */
        public static final String PURGE_DATE      = "purge_date";

        /** Written with the UTC timestamp at the time of the utility call. */
        public static final String EFFECTIVE_DATE  = "effective_date";

        /** Primary key column — UUID — present on all TIP operational tables. */
        public static final String ID              = "id";

        private OperationalTable() {}
    }

    // -----------------------------------------------------------------------
    // Result-set aliases used in RESOLVE_REGISTRY_AND_SUB_CATEGORY query
    // Must stay in sync with RetentionSql.RESOLVE_REGISTRY_AND_SUB_CATEGORY
    // -----------------------------------------------------------------------

    public static final class ResultSetAlias {
        public static final String SUB_CATEGORY_ID   = "sub_category_id";
        public static final String SUB_CATEGORY_CODE = "sub_category_code";
        public static final String DUR_VALUE         = "dur_value";
        public static final String DUR_UNIT          = "dur_unit";

        private ResultSetAlias() {}
    }

    // -----------------------------------------------------------------------
    // Duration units — values stored in retention_sub_categories
    // -----------------------------------------------------------------------

    public static final class DurationUnit {
        public static final String DAYS   = "days";
        public static final String MONTHS = "months";
        public static final String YEARS  = "years";

        private DurationUnit() {}
    }

    // -----------------------------------------------------------------------
    // SQL identifier sanitisation
    // -----------------------------------------------------------------------

    public static final class IdentifierPattern {
        /**
         * Regex that matches all valid TIP PostgreSQL identifiers.
         * Allows letters, digits, and underscores; first character must be
         * a letter or underscore.
         */
        public static final String VALID_IDENTIFIER = "[a-zA-Z_][a-zA-Z0-9_]*";

        private IdentifierPattern() {}
    }

    private RetentionConstants() {}
}
