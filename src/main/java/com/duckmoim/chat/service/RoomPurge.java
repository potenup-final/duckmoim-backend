package com.duckmoim.chat.service;

/**
 * 방 하나를 파기한 결과 (CH-19).
 *
 * <p><b>지운 메시지 수와 파기 표시 여부가 갈린다.</b> 사진의 저장소 삭제가 하나라도 실패하면 행이 남고, 그때는 메시지를 지웠어도 파기를 표시하지 않는다 — 표시하면
 * 그 방이 대상 목록에서 빠져 사진이 영영 남는다. 다음 주기가 같은 방을 다시 집어 사진만 마저 지운다.
 *
 * @param deletedMessages 이번에 지운 메시지 수. 앞 주기가 이미 지웠으면 0 이고 그것도 정상이다
 * @param purged 파기 완료를 이번에 못박았는지
 */
public record RoomPurge(int deletedMessages, boolean purged) {}
