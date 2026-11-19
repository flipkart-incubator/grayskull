package com.flipkart.grayskull.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Pins the transaction advice ahead of other aspects, so that aspects running around {@code @Transactional}
 * service methods (notably {@code AuditAspect}) execute inside the transaction. The write audit is then committed
 * or rolled back together with the change it records.
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true, order = Ordered.LOWEST_PRECEDENCE - 1)
public class TransactionConfiguration {
}
