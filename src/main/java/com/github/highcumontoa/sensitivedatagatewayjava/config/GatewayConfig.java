package com.github.highcumontoa.sensitivedatagatewayjava.config;

import com.github.highcumontoa.sensitivedatagatewayjava.limit.GatewayLimits;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用上限配置属性。
 */
@Configuration
@EnableConfigurationProperties(GatewayLimits.class)
public class GatewayConfig {
}
