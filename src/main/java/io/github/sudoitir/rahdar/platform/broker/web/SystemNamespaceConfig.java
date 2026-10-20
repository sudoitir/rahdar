package io.github.sudoitir.rahdar.platform.broker.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link SystemNamespaceInterceptor} for every cluster-scoped system namespace. */
@Configuration
class SystemNamespaceConfig implements WebMvcConfigurer {

    private final SystemNamespaceInterceptor interceptor;

    SystemNamespaceConfig(SystemNamespaceInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns(SystemNamespaceInterceptor.PATH);
    }
}
