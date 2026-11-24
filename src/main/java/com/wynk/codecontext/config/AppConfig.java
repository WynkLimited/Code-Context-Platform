package com.wynk.codecontext.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CodeContextProperties.class)
public class AppConfig {}
