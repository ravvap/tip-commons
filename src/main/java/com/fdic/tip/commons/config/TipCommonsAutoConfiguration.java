package com.fdic.tip.commons.config;

import com.fdic.tip.commons.retention.service.RetentionService;
import com.fdic.tip.commons.retention.service.RetentionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Spring Boot auto-configuration for {@code tip-commons}.
 *
 * <p>Activated automatically when {@code tip-commons} is on the classpath of a
 * Spring Boot application — no {@code @Import} or {@code @EnableXxx} needed.</p>
 *
 * <h3>What it registers</h3>
 * <ul>
 *   <li>{@link RetentionService} bean — backed by the application's primary
 *       {@link DataSource}.  Skipped if the consuming app already declares its
 *       own {@code RetentionService} bean.</li>
 *   <li>Also registers the same {@code DataSource} into the static
 *       {@link RetentionUtil} so that legacy code using static calls works
 *       without any extra setup.</li>
 * </ul>
 *
 * <h3>Plain Spring (non-Boot) projects</h3>
 * <p>Auto-configuration does not run.  Declare the bean manually:</p>
 * <pre>{@code
 * @Bean
 * public RetentionService retentionService(DataSource dataSource) {
 *     return new RetentionService(dataSource);
 * }
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnClass({ DataSource.class, RetentionService.class })
public class TipCommonsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TipCommonsAutoConfiguration.class);

    /**
     * Registers {@link RetentionService} using the application's primary
     * {@link DataSource}.
     *
     * <p>Conditions:
     * <ul>
     *   <li>A single {@code DataSource} bean exists in the context
     *       ({@code @ConditionalOnSingleCandidate}).</li>
     *   <li>No {@code RetentionService} bean is already defined
     *       ({@code @ConditionalOnMissingBean}).</li>
     * </ul>
     * </p>
     */
    @Bean
    @ConditionalOnMissingBean(RetentionService.class)
    @ConditionalOnSingleCandidate(DataSource.class)
    public RetentionService retentionService(DataSource dataSource) {
        log.info("TIP Commons: registering RetentionService (DataSource={})",
                dataSource.getClass().getSimpleName());

        // Also wire the static utility so non-injected callers work out-of-the-box
        RetentionUtil.registerDataSource(dataSource);

        return new RetentionService(dataSource);
    }
}
