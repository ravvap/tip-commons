# tip-commons

Shared utility library for **FDIC Technology Infrastructure Program (TIP)** microservices.
Works in **Spring Boot**, **plain Spring**, and **non-Spring Java** projects.

---

## Project structure

```
tip-commons/
├── pom.xml
└── src/main/java/com/fdic/tip/commons/
    │
    ├── config/
    │   └── TipCommonsAutoConfiguration.java      ← Spring Boot auto-registers beans
    │
    └── retention/
        ├── constants/
        │   ├── RetentionSql.java                 ← ALL SQL strings
        │   ├── RetentionMessages.java             ← ALL log + error messages
        │   └── RetentionConstants.java            ← Column names, schema, duration units
        ├── exception/
        │   └── RetentionException.java            ← Typed ErrorCode enum
        ├── model/
        │   └── RetentionContext.java              ← Resolved config + calculated dates
        ├── core/
        │   └── RetentionEngine.java               ← Shared logic (no Spring, no static state)
        └── service/
            ├── RetentionService.java              ← @Service bean (Spring / Spring Boot)
            └── RetentionUtil.java                 ← Static API (non-Spring projects)
```

---

## Add the dependency

```xml
<dependency>
    <groupId>com.fdic.tip</groupId>
    <artifactId>tip-commons</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

---

## Usage

### Spring Boot (recommended — zero config)

Drop the JAR on the classpath. `TipCommonsAutoConfiguration` automatically
registers a `RetentionService` bean using the application's primary `DataSource`.

```java
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepo;
    private final RetentionService  retentionService;   // injected by Spring

    @Transactional
    public Invoice create(InvoiceRequest req) throws RetentionException {
        Invoice saved = invoiceRepo.save(req.toEntity());

        // One call — no Connection, no DataSource
        retentionService.applyRetention("txn", "tbl_jpmcinvoices", saved.getId());

        return saved;
    }
}
```

### Plain Spring (manual bean declaration)

```java
@Configuration
public class AppConfig {

    @Bean
    public RetentionService retentionService(DataSource dataSource) {
        return new RetentionService(dataSource);
    }
}
```

Then inject and use exactly like the Spring Boot example above.

### Non-Spring Java (static API)

**Option A — register DataSource once at startup:**
```java
// In your application bootstrap (main(), startup listener, etc.)
RetentionUtil.registerDataSource(dataSource);

// Then anywhere in the application — no DataSource needed per call
RetentionUtil.applyRetention("txn", "tbl_jpmcinvoices", newRowId);
```

**Option B — pass DataSource explicitly per call:**
```java
RetentionUtil.applyRetention(dataSource, "txn", "tbl_jpmcinvoices", newRowId);
```

---

## Advanced: inspect before writing

Both `RetentionService` and `RetentionUtil` support a two-step flow:

```java
// Step 1: resolve — reads registry, calculates dates. No DB writes.
RetentionContext ctx = retentionService.resolve("txn", "tbl_jpmcinvoices", newId);

log.info("Purge scheduled: {}", ctx.getCalculatedPurgeDate());
if (ctx.isLegalHoldActive()) {
    log.warn("Legal hold active — purge_date will be NULL");
}

// Step 2: apply — executes the UPDATE
retentionService.apply(ctx);
```

---

## Error handling

All failures throw `RetentionException` with a structured `ErrorCode`:

| `ErrorCode` | Meaning | Action |
|---|---|---|
| `REGISTRY_NOT_FOUND` | Table not onboarded or outside effective dates | Contact retention admin |
| `REGISTRY_INACTIVE` | Registry entry `is_active = false` | Contact retention admin |
| `SUB_CATEGORY_NOT_FOUND` | Broken FK in registry | Data integrity alert |
| `BASIS_DATE_NULL` | `basis_date_column` is NULL for this row | Ensure column is populated before calling |
| `ROW_NOT_FOUND` | Row UUID doesn't exist | Check INSERT committed before calling |
| `SQL_ERROR` | JDBC / connection failure | Log + retry or rollback |
| `INVALID_ARGUMENT` | Null/blank argument | Fix caller code |

```java
} catch (RetentionException e) {
    switch (e.getErrorCode()) {
        case REGISTRY_NOT_FOUND -> alertAdmin(e);
        case BASIS_DATE_NULL    -> log.warn("Null basis for row {}", rowId);
        case SQL_ERROR          -> throw new RuntimeException("DB error", e);
        default                 -> log.error("Unexpected retention error", e);
    }
}
```

---

## Transaction behaviour

`RetentionService` and `RetentionUtil` each obtain their own connection from
the `DataSource`. To guarantee atomicity with your INSERT:

```java
// In a Spring @Transactional method — both INSERT and UPDATE share one connection
@Transactional
public Invoice create(InvoiceRequest req) throws RetentionException {
    Invoice saved = invoiceRepo.save(req.toEntity());
    retentionService.applyRetention("txn", "tbl_jpmcinvoices", saved.getId());
    return saved;
}
```

---

## Legal hold behaviour

When `retention_table_registry.legal_hold_status = TRUE`:
- `purge_date` is set to `NULL` (record must not be purged)
- `effective_date` is still stamped
- A `WARN` log entry is emitted

---

## Build commands

```bash
# Compile + run all tests
mvn clean verify

# Install to local ~/.m2 for use by other TIP projects
mvn clean install

# With sources + javadoc JARs (for IDE navigation)
mvn clean install -Prelease
```

---

## Adding a new module to tip-commons

1. Create `src/main/java/com/fdic/tip/commons/<module>/constants/` with SQL, messages, constants classes
2. Create `src/main/java/com/fdic/tip/commons/<module>/core/` for the core logic
3. Create `src/main/java/com/fdic/tip/commons/<module>/service/` for the Spring bean + static util
4. Add tests under `src/test/java/com/fdic/tip/commons/<module>/`
5. If Spring auto-registration is needed, add a `@Bean` method to `TipCommonsAutoConfiguration`
6. Update this README
7. Run `mvn clean verify` — all tests must pass
8. Bump the version in `pom.xml`

---

## Versioning

| Version | Meaning |
|---|---|
| `1.0.0-SNAPSHOT` | Active dev — `mvn install` to local `.m2` |
| `1.0.0` | Stable — deploy to Nexus/Artifactory |
| `1.1.0-SNAPSHOT` | Next feature bump |

Bump in this `pom.xml` **and** in all consuming services when releasing.
