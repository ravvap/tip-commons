package com.fdic.tip.commons.retention.service;

import com.fdic.tip.commons.retention.exception.RetentionException;
import com.fdic.tip.commons.retention.exception.RetentionException.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RetentionService}.
 *
 * Verifies that {@code RetentionService} correctly delegates to
 * {@code RetentionEngine} and validates DataSource construction.
 */
@ExtendWith(MockitoExtension.class)
class RetentionServiceTest {

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
            OffsetDateTime.of(2024, 3, 1, 9, 0, 0, 0, ZoneOffset.UTC);

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("RetentionService(null) → IllegalArgumentException")
    void construction_nullDataSource() {
        assertThatThrownBy(() -> new RetentionService(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RetentionService(validDataSource) → constructs successfully")
    void construction_valid() {
        assertThatCode(() -> new RetentionService(dataSource)).doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // applyRetention delegation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("applyRetention() → resolves and applies retention via engine")
    void applyRetention_delegatesToEngine() throws Exception {
        when(dataSource.getConnection()).thenReturn(conn);
        stubRegistry(10, "years", false);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));
        stubUpdate(1);

        RetentionService service = new RetentionService(dataSource);
        assertThatCode(() -> service.applyRetention(SCHEMA, TABLE, ROW_ID))
                .doesNotThrowAnyException();

        verify(updatePs).executeUpdate();
    }

    // -----------------------------------------------------------------------
    // resolve() + apply() two-step
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("resolve() + apply() two-step produces correct purge_date")
    void twoStep_resolveThenApply() throws Exception {
        when(dataSource.getConnection()).thenReturn(conn);
        stubRegistry(10, "years", false);
        stubBasisDate(Timestamp.from(BASIS.toInstant()));
        stubUpdate(1);

        RetentionService service = new RetentionService(dataSource);

        var ctx = service.resolve(SCHEMA, TABLE, ROW_ID);

        assertThat(ctx.getCalculatedPurgeDate()).isEqualTo(BASIS.plusYears(10));
        assertThat(ctx.isLegalHoldActive()).isFalse();

        assertThatCode(() -> service.apply(ctx)).doesNotThrowAnyException();
        verify(updatePs).setObject(eq(1), eq(BASIS.plusYears(10)));
    }

    // -----------------------------------------------------------------------
    // Error propagation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("applyRetention() → REGISTRY_NOT_FOUND propagated from engine")
    void applyRetention_registryNotFound() throws Exception {
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(registryPs);
        when(registryPs.executeQuery()).thenReturn(registryRs);
        when(registryRs.next()).thenReturn(false);

        RetentionService service = new RetentionService(dataSource);
        assertThatThrownBy(() -> service.applyRetention(SCHEMA, TABLE, ROW_ID))
                .isInstanceOf(RetentionException.class)
                .satisfies(ex -> assertThat(((RetentionException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.REGISTRY_NOT_FOUND));
    }

    // -----------------------------------------------------------------------
    // Stub helpers
    // -----------------------------------------------------------------------

    private void stubRegistry(int dur, String unit, boolean legalHold) throws Exception {
        when(conn.prepareStatement(contains("retention_table_registry"))).thenReturn(registryPs);
        when(registryPs.executeQuery()).thenReturn(registryRs);
        when(registryRs.next()).thenReturn(true);
        when(registryRs.getString("basis_date_column")).thenReturn("created_at");
        when(registryRs.getBoolean("legal_hold_status")).thenReturn(legalHold);
        when(registryRs.getString("sub_category_id")).thenReturn(SUB_ID.toString());
        when(registryRs.getString("sub_category_code")).thenReturn("invoices");
        when(registryRs.getInt("dur_value")).thenReturn(dur);
        when(registryRs.getString("dur_unit")).thenReturn(unit);
    }

    private void stubBasisDate(Timestamp ts) throws Exception {
        when(conn.prepareStatement(contains("SELECT created_at"))).thenReturn(basisPs);
        when(basisPs.executeQuery()).thenReturn(basisRs);
        when(basisRs.next()).thenReturn(true);
        when(basisRs.getTimestamp(1)).thenReturn(ts);
    }

    private void stubUpdate(int rows) throws Exception {
        when(conn.prepareStatement(contains("UPDATE txn.tbl_jpmcinvoices"))).thenReturn(updatePs);
        when(updatePs.executeUpdate()).thenReturn(rows);
    }
}
