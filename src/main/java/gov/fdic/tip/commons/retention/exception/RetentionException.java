package gov.fdic.tip.commons.retention.exception;

/**
 * Thrown by {@link com.fdic.tip.commons.retention.util.RetentionUtil} when
 * retention metadata cannot be resolved or the UPDATE to the operational
 * table fails.
 *
 * <p>All failures carry a structured {@link ErrorCode} so callers can react
 * differently per failure mode (log, alert, skip, retry, etc.).</p>
 *
 * <pre>{@code
 * } catch (RetentionException e) {
 *     switch (e.getErrorCode()) {
 *         case REGISTRY_NOT_FOUND -> alertAdmin(e);
 *         case BASIS_DATE_NULL    -> log.warn("Null basis date for row {}", rowId);
 *         case SQL_ERROR          -> throw new RuntimeException("DB failure", e);
 *         default                 -> log.error("Retention error", e);
 *     }
 * }
 * }</pre>
 */
public class RetentionException extends Exception {

    private final ErrorCode errorCode;

    public RetentionException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public RetentionException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** Structured code identifying the failure category. */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    // -----------------------------------------------------------------------
    // Error codes
    // -----------------------------------------------------------------------

    public enum ErrorCode {

        /** No row found in {@code retention_table_registry} for the given schema + table,
         *  or the entry is outside its effective date window. */
        REGISTRY_NOT_FOUND,

        /** Registry entry exists but {@code is_active = false}. */
        REGISTRY_INACTIVE,

        /** The {@code sub_category_id} FK in the registry points to a non-existent
         *  sub-category row. Indicates a data-integrity issue. */
        SUB_CATEGORY_NOT_FOUND,

        /** The basis date column named in the registry is {@code NULL} for this row.
         *  Cannot calculate {@code purge_date} without a basis date. */
        BASIS_DATE_NULL,

        /** The row targeted for the UPDATE was not found (UUID does not exist). */
        ROW_NOT_FOUND,

        /** Any underlying JDBC / SQL failure (connection issue, constraint violation, etc.). */
        SQL_ERROR,

        /** Invalid argument supplied by the caller (null connection, blank table name, etc.). */
        INVALID_ARGUMENT
    }
}
