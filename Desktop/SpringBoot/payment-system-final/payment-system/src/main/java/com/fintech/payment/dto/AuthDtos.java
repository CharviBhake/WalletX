package com.fintech.payment.dto;

import jakarta.validation.constraints.*;
import lombok.*;
import java.util.UUID;

public class AuthDtos {

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class RegisterRequest {
        @NotBlank @Size(min = 3, max = 50)
        private String username;

        @NotBlank @Email
        private String email;

        @NotBlank @Size(min = 8, max = 100)
        private String password;

        @NotBlank @Size(max = 100)
        private String fullName;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LoginRequest {
        @NotBlank private String username;
        @NotBlank private String password;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AuthResponse {
        private String token;
        private String tokenType;
        private UUID userId;
        private String username;
        private UUID walletId;
    }
}
