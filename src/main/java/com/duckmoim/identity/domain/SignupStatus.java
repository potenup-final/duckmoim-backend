package com.duckmoim.identity.domain;

/** 가입 축의 상태다. 제재 축(Safety)과 독립적이라 하나로 합치지 않는다 (도메인 모델링 6장). */
public enum SignupStatus {
  PENDING_SIGNUP_INFO,
  ACTIVE,
  WITHDRAWN
}
