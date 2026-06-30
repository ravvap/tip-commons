package com.fdic.tip.commons.retention.core;

import com.fdic.tip.commons.retention.exception.RetentionException;
import com.fdic.tip.commons.retention.exception.RetentionException.ErrorCode;
import com.fdic.tip.commons.retention.model.RetentionContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RetentionEngine}.
 * All JDBC interactions are mocked — no real database required.
 */
@ExtendWith(MockitoExtension.class)
class RetentionEngineTest {

    @Mock DataSource        dataSource;
    @Mock Connection        conn;
    @Mock PreparedStatement registryPs;
    @Mock PreparedStatement basisPs;
    @Mock PreparedStatement updatePs;
    @Mock ResultSet         registryRs;
    @Mock ResultSet         basisRs;

    private static final String SCHEMA = "txn";
    private static final String TABLE  = "tbl_jpmcinvoices";
    private static final UUID   ROW_ID = UUID.randomUUID();
    private static final UUID   SUB_ID = UUID.randomUUID();

    private static final OffsetDateTime BASIS =
            OffsetDateTime.of(2024, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC);

    private RetentionEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(conn);
        engine = new RetentionEngine(dataSource);
    }

    // -----------------------------------------------------------------------
    // resolve() — happy paths
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("resolve() → 10-year retention sets correct purge_date")
    void resolve_10Years() throws Exception {
        stubRegistry(10, "years", false);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));

        RetentionContext ctx = engine.resolve(SCHEMA, TABLE, ROW_ID);

        assertThat(ctx.getSchemaName())          .isEqualTo(SCHEMA);
        assertThat(ctx.getTableName())           .isEqualTo(TABLE);
        assertThat(ctx.getRowId())               .isEqualTo(ROW_ID);
        assertThat(ctx.getDurationValue())       .isEqualTo(10);
        assertThat(ctx.getDurationUnit())        .isEqualTo("years");
        assertThat(ctx.isLegalHoldActive())      .isFalse();
        assertThat(ctx.getBasisDate())           .isEqualTo(BASIS);
        assertThat(ctx.getCalculatedPurgeDate()) .isEqualTo(BASIS.plusYears(10));
        assertThat(ctx.getEffectiveDate())       .isNotNull();
        assertThat(ctx.getSubCategoryCode())     .isEqualTo("invoices");
    }

    @Test
    @DisplayName("resolve() → 6-month retention calculates purge_date correctly")
    void resolve_6Months() throws Exception {
        stubRegistry(6, "months", false);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));

        RetentionContext ctx = engine.resolve(SCHEMA, TABLE, ROW_ID);

        assertThat(ctx.getCalculatedPurgeDate()).isEqualTo(BASIS.plusMonths(6));
    }

    @Test
    @DisplayName("resolve() → 90-day retention calculates purge_date correctly")
    void resolve_90Days() throws Exception {
        stubRegistry(90, "days", false);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));

        RetentionContext ctx = engine.resolve(SCHEMA, TABLE, ROW_ID);

        assertThat(ctx.getCalculatedPurgeDate()).isEqualTo(BASIS.plusDays(90));
    }

    // -----------------------------------------------------------------------
    // Legal hold
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("resolve() → legal hold active → purge_date is NULL, effective_date still set")
    void resolve_legalHold_purgeIsNull() throws Exception {
        stubRegistry(10, "years", true);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));

        RetentionContext ctx = engine.resolve(SCHEMA, TABLE, ROW_ID);

        assertThat(ctx.isLegalHoldActive())      .isTrue();
        assertThat(ctx.getCalculatedPurgeDate()) .isNull();
        assertThat(ctx.getEffectiveDate())       .isNotNull();
    }

    @Test
    @DisplayName("apply() → legal hold writes NULL purge_date")
    void apply_legalHold_setsNullOnPurgeDate() throws Exception {
        stubRegistry(10, "years", true);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));
        stubUpdate(1);

        RetentionContext ctx = engine.resolve(SCHEMA, TABLE, ROW_ID);
        engine.apply(ctx);

        verify(updatePs).setNull(1, Types.TIMESTAMP_WITH_TIMEZONE);
    }

    // -----------------------------------------------------------------------
    // apply() — UPDATE verification
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("apply() → sets correct purge_date, effective_date, and rowId binds")
    void apply_setsCorrectParameters() throws Exception {
        stubRegistry(10, "years", false);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));
        stubUpdate(1);

        RetentionContext ctx = engine.resolve(SCHEMA, TABLE, ROW_ID);
        engine.apply(ctx);

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

        assertError(() -> engine.resolve(SCHEMA, TABLE, ROW_ID), ErrorCode.REGISTRY_NOT_FOUND);
    }

    @Test
    @DisplayName("resolve() → BASIS_DATE_NULL when basis column is NULL")
    void resolve_basisDateNull() throws Exception {
        stubRegistry(10, "years", false);
        when(conn.prepareStatement(contains("SELECT created_at"))).thenReturn(basisPs);
        when(basisPs.executeQuery()).thenReturn(basisRs);
        when(basisRs.next()).thenReturn(true);
        when(basisRs.getTimestamp(1)).thenReturn(null);

        assertError(() -> engine.resolve(SCHEMA, TABLE, ROW_ID), ErrorCode.BASIS_DATE_NULL);
    }

    @Test
    @DisplayName("resolve() → ROW_NOT_FOUND when basis SELECT returns no rows")
    void resolve_rowMissingDuringBasisRead() throws Exception {
        stubRegistry(10, "years", false);
        when(conn.prepareStatement(contains("SELECT created_at"))).thenReturn(basisPs);
        when(basisPs.executeQuery()).thenReturn(basisRs);
        when(basisRs.next()).thenReturn(false);

        assertError(() -> engine.resolve(SCHEMA, TABLE, ROW_ID), ErrorCode.ROW_NOT_FOUND);
    }

    @Test
    @DisplayName("apply() → ROW_NOT_FOUND when UPDATE affects 0 rows")
    void apply_zeroRowsUpdated() throws Exception {
        stubRegistry(10, "years", false);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));
        stubUpdate(0);

        RetentionContext ctx = engine.resolve(SCHEMA, TABLE, ROW_ID);
        assertError(() -> engine.apply(ctx), ErrorCode.ROW_NOT_FOUND);
    }

    @Test
    @DisplayName("apply() → SQL_ERROR wraps underlying SQLException")
    void apply_sqlExceptionWrapped() throws Exception {
        stubRegistry(10, "years", false);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));
        when(conn.prepareStatement(contains("UPDATE txn.tbl_jpmcinvoices"))).thenReturn(updatePs);
        when(updatePs.executeUpdate()).thenThrow(new SQLException("connection reset"));

        RetentionContext ctx = engine.resolve(SCHEMA, TABLE, ROW_ID);
        assertError(() -> engine.apply(ctx), ErrorCode.SQL_ERROR);
    }

    // -----------------------------------------------------------------------
    // Argument validation
    // -----------------------------------------------------------------------

    @Test @DisplayName("resolve() → null schema → INVALID_ARGUMENT")
    void validate_nullSchema() {
        assertError(() -> engine.resolve(null, TABLE, ROW_ID), ErrorCode.INVALID_ARGUMENT);
    }

    @Test @DisplayName("resolve() → blank table → INVALID_ARGUMENT")
    void validate_blankTable() {
        assertError(() -> engine.resolve(SCHEMA, "  ", ROW_ID), ErrorCode.INVALID_ARGUMENT);
    }

    @Test @DisplayName("resolve() → null rowId → INVALID_ARGUMENT")
    void validate_nullRowId() {
        assertError(() -> engine.resolve(SCHEMA, TABLE, null), ErrorCode.INVALID_ARGUMENT);
    }

    @Test @DisplayName("apply() → null context → INVALID_ARGUMENT")
    void apply_nullContext() {
        assertError(() -> engine.apply(null), ErrorCode.INVALID_ARGUMENT);
    }

    // -----------------------------------------------------------------------
    // sanitiseIdentifier static helper
    // -----------------------------------------------------------------------

    @Test @DisplayName("sanitiseIdentifier() → valid passes through")
    void sanitise_valid() throws Exception {
        assertThat(RetentionEngine.sanitiseIdentifier("tbl_jpmcinvoices"))
                .isEqualTo("tbl_jpmcinvoices");
    }

    @Test @DisplayName("sanitiseIdentifier() → hyphen rejected → INVALID_ARGUMENT")
    void sanitise_hyphenRejected() {
        assertError(() -> RetentionEngine.sanitiseIdentifier("tbl-bad"), ErrorCode.INVALID_ARGUMENT);
    }

    @Test @DisplayName("sanitiseIdentifier() → null rejected → INVALID_ARGUMENT")
    void sanitise_nullRejected() {
        assertError(() -> RetentionEngine.sanitiseIdentifier(null), ErrorCode.INVALID_ARGUMENT);
    }

    // -----------------------------------------------------------------------
    // addDuration static helper
    // -----------------------------------------------------------------------

    @Test void addDuration_years()  { assertThat(RetentionEngine.addDuration(BASIS, 10, "years"))  .isEqualTo(BASIS.plusYears(10)); }
    @Test void addDuration_months() { assertThat(RetentionEngine.addDuration(BASIS, 6, "months"))  .isEqualTo(BASIS.plusMonths(6)); }
    @Test void addDuration_days()   { assertThat(RetentionEngine.addDuration(BASIS, 90, "days"))   .isEqualTo(BASIS.plusDays(90)); }

    @Test @DisplayName("addDuration() → unknown unit → IllegalStateException")
    void addDuration_unknownUnit() {
        assertThatThrownBy(() -> RetentionEngine.addDuration(BASIS, 1, "weeks"))
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

    private void assertError(ThrowingCallable callable, ErrorCode expectedCode) {
        assertThatThrownBy(callable::call)
                .isInstanceOf(RetentionException.class)
                .satisfies(ex -> assertThat(((RetentionException) ex).getErrorCode())
                        .isEqualTo(expectedCode));
    }

    @FunctionalInterface
    interface ThrowingCallable { void call() throws Exception; }
}
