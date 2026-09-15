package com.duckmoim.safety.domain;

/**
 * 회원에게 제재를 걸었다 (AD-04).
 *
 * <p><b>Safety 가 Chat 을 모르기 위해 있다</b> (STAR-148). 도메인-모델링.md 「2. 바운디드 컨텍스트」가 의존을 {@code Chat ──▶
 * Safety} 한 방향으로 정했다. 제재가 이미 열린 채팅 스트림을 끊어야 하는데 {@code SanctionCommandService} 가 스트림 서비스를 부르면 그
 * 화살표가 뒤집힌다 — {@code UserWithdrawn} · {@code CompanionPostOpened} 가 같은 이유로 먼저 있다.
 *
 * <p><b>「무엇이 일어났다」만 적는다.</b> 어느 종류가 무엇을 막는지는 듣는 쪽이 {@code SanctionQueryService} 에 묻는다. 여기에 종류나 판정
 * 결과를 실으면 제재 표(도메인-모델링.md 「6. 라이프사이클」)가 바뀌는 날 두 곳을 고쳐야 한다.
 *
 * <p><b>구독 규약은 {@code CompanionPostOpened} 에 있다.</b> 스트림 끊기는 밖으로 나가는 호출(연결 종료 · Redis 발행)이라 그 규약의 ②
 * — 커밋 뒤에 돈다. 제재가 롤백되면 아무도 끊기지 않는다.
 *
 * <p><b>회원번호만 담는다.</b> 애그리게이트를 실어 보내면 듣는 쪽이 그것을 붙들 수 있게 되어 ID 참조 규칙이 무너진다 (도메인-모델링.md 「3.2」).
 */
public record UserSanctioned(Long userId) {}
