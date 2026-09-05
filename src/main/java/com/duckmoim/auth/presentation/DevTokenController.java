package com.duckmoim.auth.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("local")
@RestController
@RequestMapping("/api/v1/dev")
@RequiredArgsConstructor
public class DevTokenController {

  private final TokenProvider tokenProvider;

  @PostMapping("/token")
  public DevTokenResponse issueAccessToken(@Valid @RequestBody DevTokenRequest request) {
    AuthUser authUser = new AuthUser(request.userId(), request.signupCompleted(), request.admin());

    return new DevTokenResponse(tokenProvider.issueAccessToken(authUser));
  }
}
