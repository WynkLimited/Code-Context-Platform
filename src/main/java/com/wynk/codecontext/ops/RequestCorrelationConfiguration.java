package com.wynk.codecontext.ops;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class RequestCorrelationConfiguration {
    @Bean FilterRegistrationBean<RequestCorrelationFilter> requestCorrelationRegistration() {
        var registration = new FilterRegistrationBean<>(new RequestCorrelationFilter());
        registration.setName("requestCorrelation");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }
}
