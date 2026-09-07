package com.duckmoim.auth.domain;

public interface TokenProvider {

  String issueAccessToken(AuthUser authUser);

  AuthUser readAccessToken(String accessToken);
}
