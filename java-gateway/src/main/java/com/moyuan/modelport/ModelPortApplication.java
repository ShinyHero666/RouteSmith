package com.moyuan.modelport;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ModelPortApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModelPortApplication.class, args);
    }

    @Bean
    WebClient modelPortWebClient(WebClient.Builder builder) {
        return builder.build();
    }
}
