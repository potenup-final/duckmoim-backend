package com.duckmoim.companion.domain;

import java.time.LocalDate;

/**
 * 모집글이 고른 행사 (PO-01 의 선택 입력).
 *
 * <p><b>Event 애그리게이트를 객체로 붙들지 않기 위한 것이다</b> (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」). service 가 행사를 읽어 이
 * 모양으로 옮겨 넘기고, 모집글은 여기서 필요한 값만 꺼내 쓴다.
 *
 * <p><b>모집글이 복제해 갖는 것은 {@code title} 과 {@code imageUrl} 둘뿐이다.</b> 3.2 가 복제 대상을 그 둘로 한정했다 — 조인이 아니라
 * 스냅샷이라 행사 정보가 바뀌어도 모집글은 자기가 가진 값으로 그려진다.
 *
 * <p><b>{@code endsOn} 은 복제되지 않는다.</b> 만남시각을 판정하는 데만 쓰고 버린다 (I-04). 모집글 행에 넣어 DB 제약으로 한 겹 더 막는 안이
 * 있었으나, 크롤러가 종료일을 당길 때 <b>그 UPDATE 자체가 제약에 걸려 실패한다</b>는 이유로 기각됐다 (2026-09-05. 도메인-모델링.md 「확인이 남은
 * 것」).
 *
 * @param id 행사의 PK 다. 요청이 보내는 외부 식별자를 service 가 이미 풀어 놓은 값이다
 */
public record ChosenEvent(Long id, String title, String imageUrl, LocalDate endsOn) {}
