package com.rentkeeper.demo.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 데모 설정값 등록 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DemoProperties.class)
class DemoConfig {

}
