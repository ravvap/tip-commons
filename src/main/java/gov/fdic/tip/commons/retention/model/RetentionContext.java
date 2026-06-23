package gov.fdic.tip.commons.retention.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable snapshot of resolved retention configuration for a single
 * operational-table row. Created by
 * {@link com.fdic.tip.commons.retention.util.RetentionUtil#resolve} and
 * consumed by {@link com.fdic.tip.commons.retention.util.RetentionUtil#apply}.
 *
 * <p>Developers may inspect this object for logging or auditing before
 * committing the UPDATE — it contains every value the utility will write.</p>
 *
 * <h3>Legal hold note</h3>
 * <p>When {@link #isLegalHoldActive()} is {@code true},
 * {@link #getCalculatedPurgeDate()} returns {@code null}. The {@code purge_date}
 * column on the operational row will be set to {@code NULL}.</p>
 */
public final class RetentionContext {

    private final String          schemaName;
    private final String          tableName;
    private final UUID            rowId;
    private final UUID            subCategoryId;
    private final String          subCategoryCode;
    private final int             durationValue;
    private final String          durationUnit;
    private final boolean         legalHoldActive;
    private final String          basisDateColumn;
    private final OffsetDateTime  basisDate;
    private final OffsetDateTime  calculatedPurgeDate;
    private final OffsetDateTime  effectiveDate;

    /* package-private — only RetentionUtil constructs instances */
    public RetentionContext(
            String schemaName,
            String tableName,
            UUID rowId,
            UUID subCategoryId,
            String subCategoryCode,
            int durationValue,
            String durationUnit,
            boolean legalHoldActive,
            String basisDateColumn,
            OffsetDateTime basisDate,
            OffsetDateTime calculatedPurgeDate,
            OffsetDateTime effectiveDate) {

        this.schemaName          = schemaName;
        this.tableName           = tableName;
        this.rowId               = rowId;
        this.subCategoryId       = subCategoryId;
        this.subCategoryCode     = subCategoryCode;
        this.durationValue       = durationValue;
        this.durationUnit        = durationUnit;
        this.legalHoldActive     = legalHoldActive;
        this.basisDateColumn     = basisDateColumn;
        this.basisDate           = basisDate;
        this.calculatedPurgeDate = calculatedPurgeDate;
        this.effectiveDate       = effectiveDate;
    }

    /** Schema containing the operational table (e.g. {@code "txn"}). */
    public String getSchemaName()           { return schemaName; }

    /** Operational table name (e.g. {@code "tbl_jpmcinvoices"}). */
    public String getTableName()            { return tableName; }

    /** UUID of the row that was just inserted. */
    public UUID getRowId()                  { return rowId; }

    /** FK to {@code retention_sub_categories.id}. */
    public UUID getSubCategoryId()          { return subCategoryId; }

    /** Sub-category code (e.g. {@code "invoices"}). */
    public String getSubCategoryCode()      { return subCategoryCode; }

    /** Numeric retention duration (e.g. {@code 10}). */
    public int getDurationValue()           { return durationValue; }

    /** Duration unit: {@code "days"}, {@code "months"}, or {@code "years"}. */
    public String getDurationUnit()         { return durationUnit; }

    /**
     * Whether the registry entry for this table has legal hold active.
     * When {@code true}, {@link #getCalculatedPurgeDate()} is {@code null}.
     */
    public boolean isLegalHoldActive()      { return legalHoldActive; }

    /** Column name used as retention clock start (e.g. {@code "created_at"}). */
    public String getBasisDateColumn()      { return basisDateColumn; }

    /** Timestamp read from {@link #getBasisDateColumn()} for this row. */
    public OffsetDateTime getBasisDate()    { return basisDate; }

    /**
     * Computed purge date = basisDate + retention duration.
     * Returns {@code null} when {@link #isLegalHoldActive()} is {@code true}.
     */
    public OffsetDateTime getCalculatedPurgeDate() { return calculatedPurgeDate; }

    /** UTC timestamp captured when the utility ran — written to {@code effective_date}. */
    public OffsetDateTime getEffectiveDate() { return effectiveDate; }

    @Override
    public String toString() {
        return "RetentionContext{" +
               "table="          + schemaName + "." + tableName +
               ", rowId="        + rowId +
               ", subCategory="  + subCategoryCode +
               ", duration="     + durationValue + " " + durationUnit +
               ", legalHold="    + legalHoldActive +
               ", basisDate="    + basisDate +
               ", purgeDate="    + calculatedPurgeDate +
               ", effectiveDate="+ effectiveDate +
               '}';
    }
}
