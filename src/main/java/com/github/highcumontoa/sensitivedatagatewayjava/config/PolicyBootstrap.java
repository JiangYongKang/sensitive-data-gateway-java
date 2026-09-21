package com.github.highcumontoa.sensitivedatagatewayjava.config;

import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 启动时原子加载全部策略版本。
 */
@Configuration
public class PolicyBootstrap {

    @Bean
    public ApplicationRunner loadPoliciesOnStartup(PolicyStore store, PolicyLoader loader) {
        return args -> loader.loadOnStartup(store);
    }
}
