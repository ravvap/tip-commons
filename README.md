# tip-commons

Shared utility library for **FDIC Technology Infrastructure Program (TIP)** microservices.

Provides reusable, Spring-free components that any TIP service can drop in as a standard Maven dependency.

---

## Modules inside this JAR

| Package | Purpose |
|---|---|
| `com.fdic.tip.commons.retention` | Retention stamping utility — stamps `purge_date` and `effective_date` on operational table rows |
| *(future)* `com.fdic.tip.commons.audit` | Shared audit helpers |
| *(future)* `com.fdic.tip.commons.exception` | Common exception hierarchy |

---

## Build

```bash
# Compile + test
mvn clean verify

# Install to local ~/.m2 so other TIP projects can depend on it
mvn clean install

# Build with sources + javadoc JARs (for IDE navigation)
mvn clean install -Prelease

# Skip tests (not recommended)
mvn clean install -DskipTests
```

Outputs under `target/`:
- `tip-commons-1.0.0-SNAPSHOT.jar` — the library JAR
- `tip-commons-1.0.0-SNAPSHOT-sources.jar` — source JAR
- `tip-commons-1.0.0-SNAPSHOT-javadoc.jar` — Javadoc JAR
- `site/jacoco/` — code coverage report

---

## Add to a TIP microservice

After running `mvn install`, add to the consuming service's `pom.xml`:

```xml
<dependency>
    <groupId>com.fdic.tip</groupId>
    <artifactId>tip-commons</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Spring Boot services need nothing else — SLF4J is already on the classpath via Spring Boot's logging starter.

---

## Retention utility — quick usage

```java
import com.fdic.tip.commons.retention.util.RetentionUtil;
import com.fdic.tip.commons.retention.exception.RetentionException;

// After inserting a row into any table registered in retention_table_registry:
UUID newId = invoiceDao.insert(conn, invoice);

try {
    RetentionUtil.applyRetention(conn, "txn", "tbl_jpmcinvoices", newId);
} catch (RetentionException e) {
    log.error("Retention stamp failed [{}]: {}", e.getErrorCode(), e.getMessage(), e);
}
```

Full details → [`src/main/java/com/fdic/tip/commons/retention/util/RetentionUtil.java`](src/main/java/com/fdic/tip/commons/retention/util/RetentionUtil.java)

---

## Package structure

```
tip-commons/
├── pom.xml                                         ← Maven build file
├── README.md
└── src/
    ├── main/
    │   ├── java/com/fdic/tip/commons/
    │   │   └── retention/
    │   │       ├── util/
    │   │       │   └── RetentionUtil.java          ← Entry point
    │   │       ├── model/
    │   │       │   └── RetentionContext.java        ← Resolved config + dates
    │   │       └── exception/
    │   │           └── RetentionException.java      ← Typed error codes
    │   └── resources/
    │       └── META-INF/
    │           └── tip-commons.properties
    └── test/
        ├── java/com/fdic/tip/commons/retention/util/
        │   └── RetentionUtilTest.java               ← 14 unit tests (no DB)
        └── resources/
            └── logback-test.xml
```

---

## Versioning convention

| Version | Meaning |
|---|---|
| `1.0.0-SNAPSHOT` | Active development — install to local `.m2` |
| `1.0.0` | Stable release — deploy to Nexus/Artifactory |
| `1.1.0-SNAPSHOT` | Next feature increment |

Bump the version in `pom.xml` here **and** in all consuming services' `pom.xml` files when releasing.

---

## Adding a new utility to tip-commons

1. Create your package under `src/main/java/com/fdic/tip/commons/<module>/`
2. Add unit tests under `src/test/java/com/fdic/tip/commons/<module>/`
3. Update this README's module table
4. Run `mvn clean verify` — all tests must pass
5. Run `mvn install` to publish to local `.m2`
6. Bump the patch/minor version if consuming services need to pick up the change
