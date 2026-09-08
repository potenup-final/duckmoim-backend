package com.duckmoim.catalog.service;

/**
 * 적재 결과 건수 (EV-04).
 *
 * <p><b>이 넷이 EV-04 의 유일한 판정 재료다.</b> GitHub 러너에서 운영 DB 에 붙지 않기로 했으므로(D-8) 크롤러는 SQL 로 대조할 수 없고, 응답이
 * 보고하는 이 값으로만 통과를 판정한다.
 *
 * <p>통과 조건은 셋이다 — {@code events.json} 건수 == 요청 건수, 요청 건수 == {@code created + updated}, 필수 필드 결측 0건.
 * 마지막은 요청 검증에서 400 으로 걸린다 (2026-09-08 점검 「EV-04 통과 조건」).
 *
 * @param total 적재 후 누적 행수. {@code events.json} 건수와 같아지지 않는다 — 사라진 행사를 지우지 않으므로 단조 증가한다. 두 번 돌려 이 값이
 *     늘지 않는 것이 EV-03 의 멱등성 판정이다
 * @param created 이번 요청으로 새로 생긴 행
 * @param updated 이번 요청으로 이미 있던 행
 * @param hidden 기간이 남았는데 오래 안 잡혀 목록에서 빠진 행 (D-7)
 */
public record EventIngestResult(long total, int created, int updated, long hidden) {}
