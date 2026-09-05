package com.duckmoim.architecture.fixture.domain;

/** 위반 — 저장소 인터페이스가 infra 가 아니라 domain 에 선언돼 있다. */
public interface MisplacedRepository {
  String findById(Long id);
}
