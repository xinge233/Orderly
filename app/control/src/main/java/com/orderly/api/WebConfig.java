package com.orderly.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final String webLocation;

    public WebConfig(@Value("${orderly.web-root:../web}") String webRoot) {
        this.webLocation = Path.of(webRoot).toAbsolutePath().normalize().toUri().toString();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = webLocation.endsWith("/") ? webLocation : webLocation + "/";
        registry.addResourceHandler("/**").addResourceLocations(location);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/index.html");
    }
}
