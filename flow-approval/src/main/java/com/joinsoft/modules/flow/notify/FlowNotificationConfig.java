package com.joinsoft.modules.flow.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlowNotificationConfig {

    @Bean
    @ConditionalOnMissingBean(FlowNotifier.class)
    public FlowNotifier flowNotifier() {
        return new LoggingFlowNotifier();
    }
}
