package com.duckmoim.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;

  public BusinessException(ErrorCode errorCode) {
    this(errorCode, errorCode.getMessage());
  }

  /**
   * 코드의 고정 문구 대신 <b>이 요청에서만 쓸 문장</b>을 싣는다.
   *
   * <p>API-설계.md 「4. 에러 코드」가 {@code USER_SANCTIONED} 에 대해 <i>"message 에 제재 사유를 담는다. 사유는 본인에게 보여주는
   * 정보라 노출해도 된다"</i> 고 정했다. 코드 하나에 문구가 하나면 그 요구를 만족할 수 없다.
   *
   * <p><b>남용하지 않는다.</b> 에러 문구는 코드에 붙어 있는 것이 기본이고, 여기로 넘기는 순간 같은 코드가 요청마다 다른 문장을 답하게 된다. 문서가 「사유를
   * 담는다」고 적어 둔 자리에만 쓴다.
   */
  public BusinessException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }
}
