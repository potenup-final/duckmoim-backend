package com.duckmoim.companion.domain;

/**
 * 모집글이 열렸다 (PO-01).
 *
 * <p><b>Companion 이 Chat 을 모르기 위해 있다.</b> 도메인-모델링.md 「2. 바운디드 컨텍스트」가 의존을 {@code Chat ──▶ Companion}
 * 한 방향으로 정했고 <i>"B는 A를 모른다"</i> 고 읽는 법까지 적었다. CH-01 이 「모집글을 쓰면 방이 함께 생긴다」이므로 모집글 작성이 방을 만들어야 하는데,
 * {@code CompanionPostCommandService} 가 채팅 서비스를 부르면 그 화살표가 뒤집힌다.
 *
 * <p><b>그래서 이 기록은 「무엇을 해라」가 아니라 「무엇이 일어났다」다.</b> 방을 만들라는 지시가 아니라 글이 열렸다는 사실이고, 그것을 듣고 무엇을 할지는 듣는 쪽이
 * 정한다. 알림(NT)도 2차에 같은 사실을 듣게 되는데, 그때 이 기록에 손댈 일이 없어야 한다.
 *
 * <p><b>domain 에 둔다.</b> 발행하는 쪽과 듣는 쪽이 모두 service 인데 그 둘 사이에 두면 한쪽 service 가 다른 service 를 알아야 한다.
 * 어느 레이어에서나 볼 수 있는 자리는 domain 이고, 프레임워크에 묶이지 않은 record 라 아키텍처 규칙에도 걸리지 않는다.
 *
 * <p><b>모집글을 담지 않고 번호만 담는다.</b> 애그리게이트를 실어 보내면 듣는 쪽이 그것을 붙들 수 있게 되어 ID 참조 규칙이 무너진다 (도메인-모델링.md 「3.2
 * 애그리게이트 간 참조 규칙」).
 */
public record CompanionPostOpened(Long postId, Long hostId) {}
