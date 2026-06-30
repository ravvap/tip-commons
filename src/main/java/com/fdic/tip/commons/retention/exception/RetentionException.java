package com.fdic.tip.commons.retention.exception;

/**
 * Thrown by {@code RetentionService} and {@code RetentionUtil} when
 * retention metadata cannot be resolved or the UPDATE fails.
 *
 * <p>Carries a structured {@link ErrorCode} so callers react differently
 * per failure mode rather than parsing message strings.</p>
 *
 * <pre>{@code
 * } catch (RetentionException e) {
 *     switch (e.getErrorCode()) {
 *         case REGISTRY_NOT_FOUND -> alertRetentionAdmin(e);
 *         case BASIS_DATE_NULL    -> log.warn("Null basis date, row={}", rowId);
 *         case SQL_ERROR          -> throw new RuntimeException(e);
 *         default                 -> log.error("Unexpected retention error", e);
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

    public enum ErrorCode {

        /** No row in {@code retention_table_registry} for the given schema + table,
         *  or the entry is outside its effective date window. */
        REGISTRY_NOT_FOUND,

        /** Registry entry exists but {@code is_active = false}. */
        REGISTRY_INACTIVE,

        /** The {@code sub_category_id} FK points to a non-existent sub-category.
         *  Data-integrity issue — alert admin. */
        SUB_CATEGORY_NOT_FOUND,

        /** The basis date column named in the registry is {@code NULL} for this row. */
        BASIS_DATE_NULL,

        /** The row targeted for the UPDATE was not found (UUID does not exist). */
        ROW_NOT_FOUND,

        /** Any underlying JDBC / SQL failure. */
        SQL_ERROR,

        /** Invalid argument supplied by the caller (null DataSource, blank table name, etc.). */
        INVALID_ARGUMENT
    }
}
