package com.duckmoim.companion.domain;

/** 방장이 닫았는지 만남시각이 지나 배치가 닫았는지 (도메인 모델링 6장). */
public enum ClosedReason {
  MANUAL,
  MEET_TIME_PASSED
}
