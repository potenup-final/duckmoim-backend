package com.duckmoim.architecture.fixture.infra;

/** 위반 — 저장소 인터페이스가 domain 이 아니라 infra 에 선언돼 있다. */
public interface MisplacedRepository {
  String findById(Long id);
}
