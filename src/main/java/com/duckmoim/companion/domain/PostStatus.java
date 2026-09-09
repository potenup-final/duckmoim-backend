package com.duckmoim.companion.domain;

/** 모든 전이는 OPEN 에서 출발하고 CLOSED 가 종착이다. 재개방은 없다 (도메인 모델링 6장). */
public enum PostStatus {
  OPEN,
  CLOSED
}
