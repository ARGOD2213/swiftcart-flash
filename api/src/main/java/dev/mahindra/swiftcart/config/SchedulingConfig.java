package dev.mahindra.swiftcart.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Timers for the background jobs. Integration tests switch them off and drive the jobs step by step. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "swiftcart.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}
