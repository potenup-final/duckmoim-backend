package com.duckmoim.chat.infra.exif;

import java.util.Arrays;
import org.springframework.stereotype.Component;

/**
 * 사진 파일에서 위치·식별 메타데이터를 벗긴다 (CH-16).
 *
 * <p><b>재인코딩하지 않는다.</b> 이미지를 읽어 다시 쓰면 화질이 깎이고, JVM 에 WEBP 인코더가 없고, 10MB 사진을 픽셀로 풀면 힙을 크게 먹는다. 여기는
 * 컨테이너 구조만 고쳐 <b>픽셀 바이트를 한 바이트도 바꾸지 않는다.</b>
 *
 * <p><b>형식을 DB 의 {@code content_type} 이 아니라 파일 앞 바이트로 판정한다.</b> {@code content_type} 은 클라이언트가 붙인
 * 헤더라 믿을 근거가 없다 — 「image/png」로 올린 JPEG 이 형식별 처리를 빗나가면 좌표가 그대로 남는다.
 *
 * <p><b>남기는 것은 방향 한 칸이다</b> ({@link TiffOrientation}). 좌표 · 촬영 시각 · 기종 · XMP · IPTC · 텍스트 청크 · 제조사
 * 트레일러는 전부 사라진다.
 *
 * <p><b>같은 파일을 두 번 벗겨도 결과가 같다.</b> 워커가 재시도하거나 두 인스턴스가 겹쳐도 안전한 이유이고, 부르는 쪽이 <b>결과가 입력과 같으면 덮어쓰기를
 * 건너뛸</b> 수 있다.
 */
@Component
public class ImageMetadataStripper {

  private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

  /**
   * 메타데이터를 벗긴 새 파일을 준다.
   *
   * @throws UnsupportedImageException JPEG · PNG · WEBP 가 아니거나 구조가 깨졌다. <b>재시도해도 결과가 같다</b>
   */
  public byte[] strip(byte[] image) throws UnsupportedImageException {
    if (startsWith(image, JPEG_MAGIC)) {
      return JpegMetadata.strip(image);
    }
    if (startsWith(image, PngMetadata.SIGNATURE)) {
      return PngMetadata.strip(image);
    }
    if (WebpMetadata.matches(image)) {
      return WebpMetadata.strip(image);
    }
    throw new UnsupportedImageException("JPEG · PNG · WEBP 가 아니다");
  }

  private static boolean startsWith(byte[] data, byte[] prefix) {
    return data.length >= prefix.length
        && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
  }
}
