package com.duckmoim.chat.domain;

import java.time.LocalDateTime;

/**
 * 팬아웃과 SSE 에 실리는 메시지 한 건 (CH-10).
 *
 * <p>{@code ChatFanout} 의 자바독이 이 자리를 비워 두었다 — <i>"무엇을 실어 보낼지는 SSE 이벤트의 모양이 정하고 그것은 CH-10 몫이다"</i>.
 *
 * <p><b>domain 에 사는 이유는 셋이 다 만지기 때문이다.</b> service 가 만들고, infra 가 JSON 으로 바꾸고, presentation 이 응답으로
 * 그린다. service 에 두면 infra 가 service 를 참조하게 되어 레이어 규칙이 뒤집히고, presentation 에 두면 service 가 응답 DTO 를 아는
 * 셈이 된다. <b>셋 다 볼 수 있는 곳은 domain 뿐이다</b> — 프레임워크에 묶이지 않은 평범한 레코드라 그 규칙에도 걸리지 않는다.
 *
 * <p><b>보낸 사람의 표시 이름을 함께 싣는다.</b> 받는 쪽이 방 상세의 멤버 목록에서 찾게 하면 그 사이 나간 사람의 말풍선이 이름 없이 뜬다 — 목록 조회가
 * {@code AuthoredMessage} 로 같은 판단을 한 자리다 (CH-09).
 *
 * <p><b>{@code roomId} 를 담는다.</b> 채널이 이미 방마다 갈려 있어 없어도 되지만, 클라이언트가 방 여러 개를 한 화면에서 볼 때 어느 방 것인지 알 길이
 * 있어야 한다. 값이 작고 없으면 되돌리기 어렵다.
 *
 * <p><b>{@code imageId} 를 싣는다</b> (CH-14). 실시간으로 뜬 말풍선과 목록으로 받은 말풍선이 같은 모양이어야 클라이언트가 같은 배열에 넣는다 —
 * 주소가 아니라 번호인 이유는 {@code MessageItemResponse} 의 같은 필드 각주에 있다.
 *
 * @param imageId 함께 보낸 사진. 없으면 {@code null} 이다
 * @param createdAt 저장된 값 그대로 UTC 다. KST 변환은 응답을 그리는 자리에서 한다
 */
public record MessageEvent(
    Long messageId,
    Long roomId,
    Long senderId,
    String senderNickname,
    String senderProfileImageUrl,
    String content,
    Long imageId,
    MessageStatus status,
    LocalDateTime createdAt) {}
