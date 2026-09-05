package com.duckmoim.auth.presentation;

import jakarta.validation.constraints.NotNull;

public record DevTokenRequest(
    @NotNull(message = "회원번호는 필수입니다.") Long userId, boolean signupCompleted, boolean admin) {}
