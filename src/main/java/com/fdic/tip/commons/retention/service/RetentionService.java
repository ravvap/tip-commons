package com.fdic.tip.commons.retention.service;

import com.fdic.tip.commons.retention.core.RetentionEngine;
import com.fdic.tip.commons.retention.exception.RetentionException;
import com.fdic.tip.commons.retention.model.RetentionContext;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * <h2>RetentionService</h2>
 *
 * <p>Spring {@code @Service} bean for stamping {@code purge_date} and
 * {@code effective_date} on operational-table rows after insert.
 * {@code DataSource} is injected by Spring — callers never touch JDBC directly.</p>
 *
 * <h3>Spring Boot — zero-config auto-registration</h3>
 * <p>Drop {@code tip-commons} on the classpath.
 * {@code TipCommonsAutoConfiguration} registers this bean automatically
 * using Spring Boot's application {@code DataSource}.</p>
 *
 * <h3>Plain Spring — manual bean declaration</h3>
 * <pre>{@code
 * @Bean
 * public RetentionService retentionService(DataSource dataSource) {
 *     return new RetentionService(dataSource);
 * }
 * }</pre>
 *
 * <h3>Typical usage in a TIP microservice</h3>
 * <pre>{@code
 * @Service
 * @RequiredArgsConstructor
 * public class InvoiceService {
 *
 *     private final InvoiceRepository invoiceRepo;
 *     private final RetentionService  retentionService;
 *
 *     @Transactional
 *     public Invoice create(InvoiceRequest req) throws RetentionException {
 *         Invoice saved = invoiceRepo.save(req.toEntity());
 *         retentionService.applyRetention("txn", "tbl_jpmcinvoices", saved.getId());
 *         return saved;
 *     }
 * }
 * }</pre>
 *
 * <h3>Advanced — inspect before writing</h3>
 * <pre>{@code
 * RetentionContext ctx = retentionService.resolve("txn", "tbl_jpmcinvoices", id);
 * log.info("Purge scheduled: {}", ctx.getCalculatedPurgeDate());
 * retentionService.apply(ctx);
 * }</pre>
 *
 * <h3>Transaction behaviour</h3>
 * <p>Each call obtains its own connection from the injected {@link DataSource}.
 * If you need the INSERT and the retention UPDATE in the same transaction,
 * annotate your calling method with {@code @Transactional} — Spring's
 * transaction synchronisation will ensure both operations share one connection.</p>
 */
@Service
public class RetentionService {

    private final RetentionEngine engine;

    /**
     * Constructor — called by Spring or manually in plain-Spring projects.
     *
     * @param dataSource application {@link DataSource}; must not be null
     */
    public RetentionService(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException(
                    "DataSource must not be null when constructing RetentionService.");
        }
        this.engine = new RetentionEngine(dataSource);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Convenience method: resolve retention config and immediately apply the
     * UPDATE in one call.
     *
     * @param schemaName schema of the operational table (e.g. {@code "txn"})
     * @param tableName  operational table name (e.g. {@code "tbl_jpmcinvoices"})
     * @param rowId      {@link UUID} of the row just inserted
     * @throws RetentionException on any resolution or UPDATE failure
     */
    public void applyRetention(String schemaName, String tableName, UUID rowId)
            throws RetentionException {
        engine.applyRetention(schemaName, tableName, rowId);
    }

    /**
     * Step 1 of 2 (advanced): resolve retention configuration into a
     * {@link RetentionContext} without writing to the database.
     *
     * @param schemaName schema of the operational table
     * @param tableName  operational table name
     * @param rowId      UUID of the newly inserted row
     * @return populated {@link RetentionContext}
     * @throws RetentionException if configuration cannot be resolved
     */
    public RetentionContext resolve(String schemaName, String tableName, UUID rowId)
            throws RetentionException {
        return engine.resolve(schemaName, tableName, rowId);
    }

    /**
     * Step 2 of 2 (advanced): write the calculated dates from a
     * {@link RetentionContext} to the operational table row.
     *
     * @param ctx context produced by {@link #resolve}
     * @throws RetentionException if the UPDATE fails
     */
    public void apply(RetentionContext ctx) throws RetentionException {
        engine.apply(ctx);
    }
}
