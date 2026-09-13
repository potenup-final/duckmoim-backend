package com.duckmoim.admin.domain;

/**
 * 감사 로그가 남기는 행위 (AD-05).
 *
 * <p><b>다섯이다</b> (2026-09-05 확정. 화면-계약.md 「감사 로그」). 명세서 AD-05 와 화면 계약이 같은 다섯을 적고 있다.
 *
 * <p><b>신고 처리({@code REPORT})가 없다.</b> AD-03 의 「이력 기록」을 {@code Report} 가 지므로 같은 사실을 두 곳에 두지 않는다. 감사
 * 로그의 성격은 「관리자가 개인정보·비공개 내용에 접근하거나 계정에 불이익을 준 행위」로 좁혀져 있다 (도메인-모델링.md 「1. 유비쿼터스 언어」).
 *
 * <p>{@link #SECRET_READ} 가 이 표의 이유다. 채팅이 없어 비밀 댓글이 연락처가 오가는 유일한 통로라, 그것을 열람하는 것은 남의 연락처를 보는 일이다
 * (CM-17).
 */
public enum AuditKind {

  /** 제재를 걸었다 (AD-04). */
  SANCTION(AuditTargetType.USER),

  /** 제재를 풀었다 (AD-04). */
  RELEASE(AuditTargetType.USER),

  /** 비밀 댓글 본문을 열어봤다 (CM-17). */
  SECRET_READ(AuditTargetType.COMMENT),

  /** 댓글을 가렸다 (AD-07). */
  BLIND(AuditTargetType.COMMENT),

  /** 계정을 파기했다 (AU-11). */
  PURGE(AuditTargetType.USER),

  /**
   * 채팅 대화 열람 (AD-08).
   *
   * <p><b>{@link #SECRET_READ} 와 같은 이유로 남긴다.</b> 남의 사적인 대화를 보는 일이고, 신고가 접수된 건에 한해서만 열 수 있다
   * (API-설계.md 「2-7. 백오피스 (Admin)」). 열람 자격은 넘긴 신고 번호가 지고, 이 기록은 <b>누가 언제 무엇을 봤는지</b>를 남긴다.
   *
   * <p>대상이 메시지가 아니라 <b>방</b>인 것은 열람 단위가 대화 전체이기 때문이다. 어느 신고를 처리하다 열었는지는 {@code detail} 에 적힌다.
   */
  CHAT_READ(AuditTargetType.CHAT_ROOM),

  /**
   * 메시지 블라인드 (AD-09).
   *
   * <p><b>{@link #BLIND} 를 재사용하지 않는다.</b> 이 enum 은 종류마다 대상 타입을 하나씩 묶어 두는데 ({@code BLIND} → {@code
   * COMMENT}), 메시지를 거기 끼우면 그 일대일이 깨진다. 화면-계약.md 「감사 로그」가 같은 이유를 적어 두었다.
   */
  MESSAGE_BLIND(AuditTargetType.MESSAGE);

  private final AuditTargetType targetType;

  AuditKind(AuditTargetType targetType) {
    this.targetType = targetType;
  }

  /**
   * 이 행위가 가리키는 대상의 종류.
   *
   * <p>행위마다 대상이 하나로 정해져 있어 기록하는 쪽이 둘을 따로 주지 않는다. 따로 받으면 {@code BLIND} 인데 {@code USER} 를 가리키는 기록이
   * 만들어질 수 있고, 감사 로그는 고칠 수 없어 그 줄이 영영 남는다 (I-13).
   */
  public AuditTargetType targetType() {
    return targetType;
  }
}
