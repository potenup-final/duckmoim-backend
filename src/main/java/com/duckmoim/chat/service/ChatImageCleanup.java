package com.duckmoim.chat.service;

/**
 * 한 주기의 고아 정리 결과 (CH-17).
 *
 * <p><b>집은 수와 지운 수를 따로 돌려준다.</b> 배치가 「더 있나」를 <b>집은 수</b>로 판정해야 하기 때문이다 — 지운 수로 끊으면 저장소 삭제가 실패한 주기에
 * 아직 남은 고아를 두고 배치가 끝난다 ({@code NotificationExpiry} 가 같은 이유로 같은 모양이다).
 *
 * @param picked 이번 청크가 집은 행 수. 상한보다 적으면 더 없다
 * @param deleted 저장소와 표에서 실제로 지운 수
 */
public record ChatImageCleanup(int picked, int deleted) {}
