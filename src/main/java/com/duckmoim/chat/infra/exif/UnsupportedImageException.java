package com.duckmoim.chat.infra.exif;

/**
 * 메타데이터를 벗길 수 없는 파일 (CH-16).
 *
 * <p><b>검사 예외인 것이 의도다.</b> 이 예외는 「몇 번 다시 해도 결과가 같다」는 뜻이라 부르는 쪽이 <b>재시도하지 않고 바로 {@code FAILED}</b> 로
 * 보내야 한다. 네트워크 장애 같은 일시적 실패({@code RuntimeException})와 갈라 받게 하려고 컴파일러가 강제하게 둔다.
 *
 * <p><b>메시지에 파일 내용을 담지 않는다.</b> 어느 구조가 어긋났는지만 적는다.
 */
public class UnsupportedImageException extends Exception {

  public UnsupportedImageException(String reason) {
    super(reason);
  }
}
