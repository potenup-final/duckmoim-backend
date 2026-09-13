package com.duckmoim.common.infra;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationMute;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 끈 알림 종류를 읽고 쓴다 (NT-11).
 *
 * <p><b>읽는 자리가 둘이다.</b> 아웃박스 발행 직전과 설정 화면이고, {@code uq_notification_mute (user_id, kind)} 하나가 둘 다
 * 받는다 (V807).
 */
public interface NotificationMuteRepository extends JpaRepository<NotificationMute, Long> {

  /** 이 사람이 이 종류를 껐는가. <b>댓글 알림의 발행 직전</b>이 부르는 자리다 (수신자가 한 명이다). */
  boolean existsByUserIdAndKind(Long userId, NotificationKind kind);

  /**
   * 준 사람들 중 이 종류를 끈 사람. <b>채팅 알림의 발행 직전</b>이 부르는 자리다 (수신자가 여럿이다).
   *
   * <p><b>한 번에 묻는 것이 요점이다.</b> 한 사람씩 물으면 6명 방의 메시지 한 건이 조회 다섯이 되고, 그 자리는 메시지 전송 트랜잭션 안이다.
   *
   * <p>엔티티가 아니라 회원번호만 돌려준다 — 부르는 쪽이 하는 일이 「이 목록에서 빼기」 하나다.
   */
  @Query("select m.userId from NotificationMute m where m.kind = :kind and m.userId in :userIds")
  List<Long> findMutedUserIds(
      @Param("kind") NotificationKind kind, @Param("userIds") Collection<Long> userIds);

  /** 이 사람이 끈 종류 전부. <b>설정 화면</b>이 부르는 자리다. */
  @Query("select m.kind from NotificationMute m where m.userId = :userId")
  List<NotificationKind> findMutedKinds(@Param("userId") Long userId);

  /**
   * 이 사람의 설정을 통째로 지운다. 저장이 「지우고 다시 넣기」라 그 앞단이다.
   *
   * <p><b>지운 뒤에 다시 넣는 이유는 PUT 이기 때문이다.</b> 무엇이 늘고 줄었는지 따져 부분만 고치면 계산이 하나 더 생기는데, 한 사람의 행이 많아야 종류
   * 수(셋)다.
   *
   * <p><b>이름으로 만든 파생 삭제가 아니라 벌크 삭제여야 한다</b> (PR #148 리뷰). 파생 삭제는 읽어서 {@code em.remove} 를 부를 뿐이라 실제
   * {@code DELETE} 가 flush 까지 미뤄지는데, {@link NotificationMute} 가 {@code IDENTITY} 라 뒤따르는 {@code
   * save} 의 {@code INSERT} 는 채번하려고 <b>즉시 실행된다.</b> 그래서 이미 끈 종류를 다시 보내면 {@code uq_notification_mute}
   * 에 부딪힌다.
   *
   * <pre>
   * 1차 PUT  채팅만 끔        지울 행이 없어 통과한다
   * 2차 PUT  채팅 + 댓글 끔   ROOM_MESSAGED 를 다시 넣다가 Duplicate entry
   * </pre>
   *
   * <p><b>{@code flush()} 를 끼워 넣는 것으로도 되지만 이쪽을 골랐다.</b> 파생 삭제는 {@code SELECT} 한 번에 행마다 {@code
   * DELETE} 한 번이고, 벌크는 그것을 한 문장으로 준다. 순서를 지키려고 {@code flush} 를 부르는 것보다 <b>애초에 미뤄지지 않는 문장</b>을 쓰는 편이
   * 다음 사람이 잘못 건드릴 자리도 적다.
   *
   * <p>영속성 컨텍스트를 지나치지만 여기서는 문제가 없다 — 부르는 쪽이 이 트랜잭션에서 {@code NotificationMute} 를 읽어 둔 적이 없다.
   */
  @Modifying
  @Query("delete from NotificationMute m where m.userId = :userId")
  void deleteByUserId(@Param("userId") Long userId);
}
