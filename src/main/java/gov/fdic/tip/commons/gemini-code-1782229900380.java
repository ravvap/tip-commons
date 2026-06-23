import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import gov.fdic.tip.commons.retention.util.RetentionUtil;
import gov.fdic.tip.commons.retention.exception.RetentionException;

public class Main {

    // Using H2 in-memory database with PostgreSQL compatibility mode for testing
private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/your_database_name";
    private static final String USER = "postgres";
    private static final String PASSWORD = "your_secure_password";
    public static void main(String[] args) {
        System.out.println("--- Starting RetentionUtil Integration Test ---");

        try (Connection conn = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            // Turn off auto-commit to simulate a proper transactional context
            conn.setAutoCommit(false);

            // 1. Setup Mock Tables and Data
            setupDatabaseSchema(conn);

            // 2. Generate a fresh UUID for our operational record test
            UUID recordId = UUID.randomUUID();
            System.out.println("Generated operational row ID: " + recordId);

            // 3. Insert the operational record with a 'basis date' (created_at)
            insertOperationalRecord(conn, recordId);

            // 4. Call RetentionUtil to calculate and stamp the purge_date
            System.out.println("\nExecuting RetentionUtil.applyRetention...");
            RetentionUtil.applyRetention(conn, "txn", "tbl_jpmcinvoices", recordId);
            System.out.println("Retention applied successfully!");

            // 5. Verify the updates took place
            verifyStampedDates(conn, recordId);

            // Commit the transaction
            conn.commit();
            System.out.println("\n--- Test Finished Successfully & Transaction Committed ---");

        } catch (RetentionException e) {
            System.err.printf("Retention Rule Failed [%s]: %s%n", e.getErrorCode(), e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Unexpected System Error occurred during testing:");
            e.printStackTrace();
        }
    }

    private static void setupDatabaseSchema(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            // Create target schema
            stmt.execute("CREATE SCHEMA IF NOT EXISTS txn");

            // Create configuration tables mimicking your registry query
            stmt.execute("CREATE TABLE txn.retention_sub_categories (" +
                    "id UUID PRIMARY KEY, " +
                    "code VARCHAR(50), " +
                    "retention_duration_value INT, " +
                    "retention_duration_unit VARCHAR(20))");

            stmt.execute("CREATE TABLE txn.retention_table_registry (" +
                    "schema_name VARCHAR(50), " +
                    "table_name VARCHAR(50), " +
                    "basis_date_column VARCHAR(50), " +
                    "legal_hold_status BOOLEAN, " +
                    "sub_category_id UUID, " +
                    "is_active BOOLEAN, " +
                    "deleted_at TIMESTAMP, " +
                    "rtn_effective_date TIMESTAMP, " +
                    "rtn_end_date TIMESTAMP)");

            // Create dummy operational data table
            stmt.execute("CREATE TABLE txn.tbl_jpmcinvoices (" +
                    "id UUID PRIMARY KEY, " +
                    "invoice_amount DECIMAL, " +
                    "created_at TIMESTAMP, " +
                    "purge_date TIMESTAMP WITH TIME ZONE, " +
                    "effective_date TIMESTAMP WITH TIME ZONE)");

            // Populate retention metadata setup: Keep data for 7 Years
            UUID subCategoryId = UUID.randomUUID();

            try (PreparedStatement psSub = conn.prepareStatement(
                    "INSERT INTO txn.retention_sub_categories VALUES (?, 'INVOICE_RULE', 7, 'years')")) {
                psSub.setObject(1, subCategoryId);
                psSub.executeUpdate();
            }

            try (PreparedStatement psReg = conn.prepareStatement(
                    "INSERT INTO txn.retention_table_registry VALUES ('txn', 'tbl_jpmcinvoices', 'created_at', false, ?, true, null, ?, ?)")) {
                psReg.setObject(1, subCategoryId);
                psReg.setTimestamp(2, Timestamp.valueOf(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toLocalDateTime())); // active since yesterday
                psReg.setTimestamp(3, Timestamp.valueOf(OffsetDateTime.now(ZoneOffset.UTC).plusYears(1).toLocalDateTime()));  // active until next year
                psReg.executeUpdate();
            }

            System.out.println("Mock registry tables and schema built successfully.");
        }
    }

    private static void insertOperationalRecord(Connection conn, UUID recordId) throws Exception {
        String sql = "INSERT INTO txn.tbl_jpmcinvoices (id, invoice_amount, created_at, purge_date, effective_date) VALUES (?, ?, ?, null, null)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, recordId);
            ps.setDouble(2, 1500.75);
            // Basis date set to right now
            ps.setTimestamp(3, Timestamp.valueOf(OffsetDateTime.now(ZoneOffset.UTC).toLocalDateTime()));
            ps.executeUpdate();
        }
        System.out.println("Operational invoice record inserted into 'txn.tbl_jpmcinvoices'.");
    }

    private static void verifyStampedDates(Connection conn, UUID recordId) throws Exception {
        String sql = "SELECT created_at, purge_date, effective_date FROM txn.tbl_jpmcinvoices WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, recordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    System.out.println("\n--- Database Verification Verification ---");
                    System.out.println("Basis Date (created_at):   " + rs.getTimestamp("created_at"));
                    System.out.println("Stamped Purge Date:        " + rs.getObject("purge_date"));
                    System.out.println("Stamped Effective Date:    " + rs.getObject("effective_date"));
                } else {
                    System.err.println("Verification failed: Record went missing!");
                }
            }
        }
    }
}