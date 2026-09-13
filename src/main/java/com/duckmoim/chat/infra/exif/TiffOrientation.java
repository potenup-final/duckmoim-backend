package com.duckmoim.chat.infra.exif;

import java.util.OptionalInt;

/**
 * EXIF(TIFF 블록)에서 방향 한 칸만 읽고, 방향 한 칸만 담은 블록을 새로 쓴다 (CH-16).
 *
 * <p><b>왜 방향만 남기나.</b> EXIF 를 통째로 지우면 센서 방향 그대로 저장하고 {@code Orientation} 으로 돌려 보여주는 기기(안드로이드에 많다)의
 * 세로 사진이 <b>옆으로 누워서 뜬다.</b> 방향은 위치도 식별도 아니다 — 1 에서 8 사이의 숫자 하나다.
 *
 * <p><b>원본 블록을 고쳐 쓰지 않고 새로 만든다.</b> 원본에서 GPS IFD 포인터나 엔트리를 골라 지우는 방식은 오프셋이 얽혀 있어 하나를 놓치면 좌표가 남는다.
 * 방향 값 하나만 꺼내고 나머지는 전부 버리면 <b>남는 것이 구조적으로 그 한 칸뿐</b>이다.
 *
 * <pre>
 * 만드는 블록 (26바이트, 리틀엔디언)
 *   0  'I' 'I'  42          헤더
 *   4  8                    IFD0 위치
 *   8  1                    엔트리 수
 *  10  0x0112 SHORT 1 값    Orientation
 *  22  0                    다음 IFD 없음
 * </pre>
 */
final class TiffOrientation {

  private static final int TAG_ORIENTATION = 0x0112;
  private static final int TYPE_SHORT = 3;
  private static final int ENTRY_SIZE = 12;

  private TiffOrientation() {}

  /**
   * TIFF 블록의 IFD0 에서 방향 값을 읽는다.
   *
   * <p><b>읽을 수 없으면 던지지 않고 빈 값이다.</b> 방향은 남기면 좋은 것이지 없어도 되는 것이라, 블록이 깨졌다고 사진 전체를 실패시키지 않는다 — 그 블록은
   * 어차피 버려진다.
   */
  static OptionalInt read(byte[] data, int offset, int length) {
    if (length < 8 || offset < 0 || offset + length > data.length) {
      return OptionalInt.empty();
    }

    boolean little;
    if (data[offset] == 'I' && data[offset + 1] == 'I') {
      little = true;
    } else if (data[offset] == 'M' && data[offset + 1] == 'M') {
      little = false;
    } else {
      return OptionalInt.empty();
    }

    if (u16(data, offset + 2, little) != 42) {
      return OptionalInt.empty();
    }

    long ifd = u32(data, offset + 4, little);
    if (ifd < 8 || ifd + 2 > length) {
      return OptionalInt.empty();
    }

    int start = offset + (int) ifd;
    int count = u16(data, start, little);
    for (int i = 0; i < count; i++) {
      int entry = start + 2 + i * ENTRY_SIZE;
      if (entry + ENTRY_SIZE > offset + length) {
        return OptionalInt.empty();
      }
      if (u16(data, entry, little) == TAG_ORIENTATION
          && u16(data, entry + 2, little) == TYPE_SHORT) {
        int value = u16(data, entry + 8, little);
        return value >= 1 && value <= 8 ? OptionalInt.of(value) : OptionalInt.empty();
      }
    }
    return OptionalInt.empty();
  }

  /** 방향 한 칸만 담은 TIFF 블록. */
  static byte[] orientationOnly(int orientation) {
    return new byte[] {
      'I',
      'I',
      42,
      0, // 헤더
      8,
      0,
      0,
      0, // IFD0 위치
      1,
      0, // 엔트리 수
      0x12,
      0x01,
      TYPE_SHORT,
      0,
      1,
      0,
      0,
      0,
      (byte) orientation,
      0,
      0,
      0, // Orientation
      0,
      0,
      0,
      0 // 다음 IFD 없음
    };
  }

  private static int u16(byte[] b, int i, boolean little) {
    int a = b[i] & 0xFF;
    int c = b[i + 1] & 0xFF;
    return little ? a | c << 8 : a << 8 | c;
  }

  private static long u32(byte[] b, int i, boolean little) {
    long v0 = b[i] & 0xFF;
    long v1 = b[i + 1] & 0xFF;
    long v2 = b[i + 2] & 0xFF;
    long v3 = b[i + 3] & 0xFF;
    return little ? v0 | v1 << 8 | v2 << 16 | v3 << 24 : v0 << 24 | v1 << 16 | v2 << 8 | v3;
  }
}
