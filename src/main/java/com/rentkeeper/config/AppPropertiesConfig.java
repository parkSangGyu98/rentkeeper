package com.rentkeeper.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.rentkeeper.common.config.AppProperties;
import com.rentkeeper.common.crypto.EncryptionProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AppProperties.class, EncryptionProperties.class})
public class AppPropertiesConfig {

}
