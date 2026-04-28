package com.fintech.payment.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "FintechPay — Payment System API",
        version = "1.0.0",
        description = """
            Production-grade payment system with:
            - Wallet management (balance, deposit, withdraw)
            - Peer-to-peer transfers
            - Idempotent operations (Idempotency-Key header)
            - Async payment processing via RabbitMQ
            - Immutable double-entry ledger
            - Full transaction state machine: PENDING → PROCESSING → SUCCESS/FAILED
            """,
        contact = @Contact(name = "FintechPay Engineering")
    ),
    servers = @Server(url = "http://localhost:8080", description = "Local development")
)
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    in = SecuritySchemeIn.HEADER,
    description = "JWT token obtained from /api/v1/auth/login"
)
public class OpenApiConfig {
    // Configuration via annotations — no beans needed
}
