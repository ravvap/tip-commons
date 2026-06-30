package com.fdic.tip.commons.retention.util;

import com.fdic.tip.commons.retention.exception.RetentionException;
import com.fdic.tip.commons.retention.exception.RetentionException.ErrorCode;
import com.fdic.tip.commons.retention.model.RetentionContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.*;
import java.time.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RetentionUtil}.
 * All JDBC interactions are mocked — no real database required.
 */
@ExtendWith(MockitoExtension.class)
class RetentionUtilTest {

    // -----------------------------------------------------------------------
    // Mocks
    // -----------------------------------------------------------------------
    @Mock Connection        conn;
    @Mock PreparedStatement registryPs;
    @Mock PreparedStatement basisPs;
    @Mock PreparedStatement updatePs;
    @Mock ResultSet         registryRs;
    @Mock ResultSet         basisRs;

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------
    private static final String SCHEMA   = "txn";
    private static final String TABLE    = "tbl_jpmcinvoices";
    private static final UUID   ROW_ID   = UUID.randomUUID();
    private static final UUID   SUB_ID   = UUID.randomUUID();

    // Fixed basis date for deterministic assertions
    private static final OffsetDateTime BASIS =
            OffsetDateTime.of(2024, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC);

    // -----------------------------------------------------------------------
    // resolve() — happy paths
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("resolve() → 10-year retention produces correct purge_date")
    void resolve_10Years() throws Exception {
        stubRegistry(10, "years", false);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));

        RetentionContext ctx = RetentionUtil.resolve(conn, SCHEMA, TABLE, ROW_ID);

        assertThat(ctx.getSchemaName())           .isEqualTo(SCHEMA);
        assertThat(ctx.getTableName())            .isEqualTo(TABLE);
        assertThat(ctx.getRowId())                .isEqualTo(ROW_ID);
        assertThat(ctx.getDurationValue())        .isEqualTo(10);
        assertThat(ctx.getDurationUnit())         .isEqualTo("years");
        assertThat(ctx.isLegalHoldActive())       .isFalse();
        assertThat(ctx.getBasisDate())            .isEqualTo(BASIS);
        assertThat(ctx.getCalculatedPurgeDate())  .isEqualTo(BASIS.plusYears(10));
        assertThat(ctx.getEffectiveDate())        .isNotNull();
    }

    @Test
    @DisplayName("resolve() → 6-month retention produces correct purge_date")
    void resolve_6Months() throws Exception {
        stubRegistry(6, "months", false);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));

        RetentionContext ctx = RetentionUtil.resolve(conn, SCHEMA, TABLE, ROW_ID);

        assertThat(ctx.getCalculatedPurgeDate()).isEqualTo(BASIS.plusMonths(6));
    }

    @Test
    @DisplayName("resolve() → 90-day retention produces correct purge_date")
    void resolve_90Days() throws Exception {
        stubRegistry(90, "days", false);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));

        RetentionContext ctx = RetentionUtil.resolve(conn, SCHEMA, TABLE, ROW_ID);

        assertThat(ctx.getCalculatedPurgeDate()).isEqualTo(BASIS.plusDays(90));
    }

    // -----------------------------------------------------------------------
    // Legal hold
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("resolve() → legal hold active means purge_date is NULL, effective_date still set")
    void resolve_legalHold_purgeIsNull() throws Exception {
        stubRegistry(10, "years", true);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));

        RetentionContext ctx = RetentionUtil.resolve(conn, SCHEMA, TABLE, ROW_ID);

        assertThat(ctx.isLegalHoldActive())      .isTrue();
        assertThat(ctx.getCalculatedPurgeDate()) .isNull();
        assertThat(ctx.getEffectiveDate())       .isNotNull();
    }

    @Test
    @DisplayName("apply() → legal hold writes NULL purge_date via setNull()")
    void apply_legalHold_setsNullOnPurgeDate() throws Exception {
        stubRegistry(10, "years", true);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));
        stubUpdate(1);

        RetentionContext ctx = RetentionUtil.resolve(conn, SCHEMA, TABLE, ROW_ID);
        RetentionUtil.apply(conn, ctx);

        verify(updatePs).setNull(1, Types.TIMESTAMP_WITH_TIMEZONE);
        verify(updatePs).setObject(eq(2), eq(ctx.getEffectiveDate()));
        verify(updatePs).setObject(eq(3), eq(ROW_ID));
    }

    // -----------------------------------------------------------------------
    // apply() — UPDATE behaviour
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("apply() → executes UPDATE with correct purge_date and effective_date")
    void apply_executesUpdateCorrectly() throws Exception {
        stubRegistry(10, "years", false);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));
        stubUpdate(1);

        RetentionContext ctx = RetentionUtil.resolve(conn, SCHEMA, TABLE, ROW_ID);
        RetentionUtil.apply(conn, ctx);

        verify(updatePs).setObject(eq(1), eq(BASIS.plusYears(10)));
        verify(updatePs).setObject(eq(2), eq(ctx.getEffectiveDate()));
        verify(updatePs).setObject(eq(3), eq(ROW_ID));
        verify(updatePs).executeUpdate();
    }

    // -----------------------------------------------------------------------
    // Error paths
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("resolve() → REGISTRY_NOT_FOUND when no active registry entry")
    void resolve_registryMissing() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(registryPs);
        when(registryPs.executeQuery()).thenReturn(registryRs);
        when(registryRs.next()).thenReturn(false);

        assertRetentionError(
                () -> RetentionUtil.resolve(conn, SCHEMA, TABLE, ROW_ID),
                ErrorCode.REGISTRY_NOT_FOUND);
    }

    @Test
    @DisplayName("resolve() → BASIS_DATE_NULL when basis column is NULL")
    void resolve_basisDateIsNull() throws Exception {
        stubRegistry(10, "years", false);
        when(conn.prepareStatement(contains("SELECT created_at"))).thenReturn(basisPs);
        when(basisPs.executeQuery()).thenReturn(basisRs);
        when(basisRs.next()).thenReturn(true);
        when(basisRs.getTimestamp(1)).thenReturn(null);

        assertRetentionError(
                () -> RetentionUtil.resolve(conn, SCHEMA, TABLE, ROW_ID),
                ErrorCode.BASIS_DATE_NULL);
    }

    @Test
    @DisplayName("resolve() → ROW_NOT_FOUND when basis date SELECT returns no rows")
    void resolve_rowMissingDuringBasisRead() throws Exception {
        stubRegistry(10, "years", false);
        when(conn.prepareStatement(contains("SELECT created_at"))).thenReturn(basisPs);
        when(basisPs.executeQuery()).thenReturn(basisRs);
        when(basisRs.next()).thenReturn(false);

        assertRetentionError(
                () -> RetentionUtil.resolve(conn, SCHEMA, TABLE, ROW_ID),
                ErrorCode.ROW_NOT_FOUND);
    }

    @Test
    @DisplayName("apply() → ROW_NOT_FOUND when UPDATE affects 0 rows")
    void apply_updateAffectsZeroRows() throws Exception {
        stubRegistry(10, "years", false);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));
        stubUpdate(0); // nothing updated

        RetentionContext ctx = RetentionUtil.resolve(conn, SCHEMA, TABLE, ROW_ID);

        assertRetentionError(() -> RetentionUtil.apply(conn, ctx), ErrorCode.ROW_NOT_FOUND);
    }

    @Test
    @DisplayName("apply() → SQL_ERROR wraps SQLException from UPDATE")
    void apply_sqlException_wrappedAsSqlError() throws Exception {
        stubRegistry(10, "years", false);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));
        when(conn.prepareStatement(contains("UPDATE txn.tbl_jpmcinvoices"))).thenReturn(updatePs);
        when(updatePs.executeUpdate()).thenThrow(new SQLException("connection lost"));

        RetentionContext ctx = RetentionUtil.resolve(conn, SCHEMA, TABLE, ROW_ID);

        assertRetentionError(() -> RetentionUtil.apply(conn, ctx), ErrorCode.SQL_ERROR);
    }

    // -----------------------------------------------------------------------
    // Argument validation
    // -----------------------------------------------------------------------

    @Test @DisplayName("applyRetention() → null Connection → INVALID_ARGUMENT")
    void validate_nullConnection() {
        assertRetentionError(
                () -> RetentionUtil.applyRetention(null, SCHEMA, TABLE, ROW_ID),
                ErrorCode.INVALID_ARGUMENT);
    }

    @Test @DisplayName("applyRetention() → blank schemaName → INVALID_ARGUMENT")
    void validate_blankSchema() {
        assertRetentionError(
                () -> RetentionUtil.applyRetention(conn, "  ", TABLE, ROW_ID),
                ErrorCode.INVALID_ARGUMENT);
    }

    @Test @DisplayName("applyRetention() → null tableName → INVALID_ARGUMENT")
    void validate_nullTable() {
        assertRetentionError(
                () -> RetentionUtil.applyRetention(conn, SCHEMA, null, ROW_ID),
                ErrorCode.INVALID_ARGUMENT);
    }

    @Test @DisplayName("applyRetention() → null rowId → INVALID_ARGUMENT")
    void validate_nullRowId() {
        assertRetentionError(
                () -> RetentionUtil.applyRetention(conn, SCHEMA, TABLE, null),
                ErrorCode.INVALID_ARGUMENT);
    }

    // -----------------------------------------------------------------------
    // sanitiseIdentifier unit tests (package-private method)
    // -----------------------------------------------------------------------

    @Test @DisplayName("sanitiseIdentifier() → valid identifier passes through")
    void sanitise_valid() throws Exception {
        assertThat(RetentionUtil.sanitiseIdentifier("tbl_jpmcinvoices"))
                .isEqualTo("tbl_jpmcinvoices");
    }

    @Test @DisplayName("sanitiseIdentifier() → identifier with hyphen throws INVALID_ARGUMENT")
    void sanitise_hyphenRejected() {
        assertRetentionError(
                () -> RetentionUtil.sanitiseIdentifier("tbl-bad"),
                ErrorCode.INVALID_ARGUMENT);
    }

    @Test @DisplayName("sanitiseIdentifier() → null throws INVALID_ARGUMENT")
    void sanitise_nullRejected() {
        assertRetentionError(
                () -> RetentionUtil.sanitiseIdentifier(null),
                ErrorCode.INVALID_ARGUMENT);
    }

    // -----------------------------------------------------------------------
    // addDuration unit tests (package-private method)
    // -----------------------------------------------------------------------

    @Test @DisplayName("addDuration() → years")
    void addDuration_years() {
        assertThat(RetentionUtil.addDuration(BASIS, 10, "years"))
                .isEqualTo(BASIS.plusYears(10));
    }

    @Test @DisplayName("addDuration() → months")
    void addDuration_months() {
        assertThat(RetentionUtil.addDuration(BASIS, 6, "months"))
                .isEqualTo(BASIS.plusMonths(6));
    }

    @Test @DisplayName("addDuration() → days")
    void addDuration_days() {
        assertThat(RetentionUtil.addDuration(BASIS, 90, "days"))
                .isEqualTo(BASIS.plusDays(90));
    }

    @Test @DisplayName("addDuration() → unknown unit throws IllegalStateException")
    void addDuration_unknownUnit() {
        assertThatThrownBy(() -> RetentionUtil.addDuration(BASIS, 1, "weeks"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("weeks");
    }

    // -----------------------------------------------------------------------
    // Stub helpers
    // -----------------------------------------------------------------------

    private void stubRegistry(int durationValue, String durationUnit, boolean legalHold)
            throws Exception {
        when(conn.prepareStatement(contains("retention_table_registry"))).thenReturn(registryPs);
        when(registryPs.executeQuery()).thenReturn(registryRs);
        when(registryRs.next()).thenReturn(true);
        when(registryRs.getString("basis_date_column")).thenReturn("created_at");
        when(registryRs.getBoolean("legal_hold_status")).thenReturn(legalHold);
        when(registryRs.getString("sub_category_id")).thenReturn(SUB_ID.toString());
        when(registryRs.getString("sub_category_code")).thenReturn("invoices");
        when(registryRs.getInt("dur_value")).thenReturn(durationValue);
        when(registryRs.getString("dur_unit")).thenReturn(durationUnit);
    }

    private void stubBasisDate(Timestamp ts) throws Exception {
        when(conn.prepareStatement(contains("SELECT created_at"))).thenReturn(basisPs);
        when(basisPs.executeQuery()).thenReturn(basisRs);
        when(basisRs.next()).thenReturn(true);
        when(basisRs.getTimestamp(1)).thenReturn(ts);
    }

    private void stubUpdate(int rowsAffected) throws Exception {
        when(conn.prepareStatement(contains("UPDATE txn.tbl_jpmcinvoices"))).thenReturn(updatePs);
        when(updatePs.executeUpdate()).thenReturn(rowsAffected);
    }

    /** Asserts that a ThrowingCallable throws a {@link RetentionException} with the given code. */
    private void assertRetentionError(ThrowingCallable callable, ErrorCode expectedCode) {
        assertThatThrownBy(callable::call)
                .isInstanceOf(RetentionException.class)
                .satisfies(ex ->
                    assertThat(((RetentionException) ex).getErrorCode())
                            .isEqualTo(expectedCode));
    }

    @FunctionalInterface
    interface ThrowingCallable {
        void call() throws Exception;
    }
}
