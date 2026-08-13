package com.tuum.banking.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI coreBankingOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Core Banking Service")
                .version("1.0.0")
                .description("Accounts, per-currency balances and transaction history."));
    }
}
