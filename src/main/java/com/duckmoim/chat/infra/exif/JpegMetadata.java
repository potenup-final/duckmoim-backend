package com.duckmoim.chat.infra.exif;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;

/**
 * JPEG 의 메타데이터 세그먼트를 걷어낸다 (CH-16).
 *
 * <p><b>남길 것을 적고 나머지를 버린다</b> — 지울 것을 적는 방식이면 모르는 제조사 세그먼트에 좌표가 실려 와도 통과한다.
 *
 * <pre>
 * 남긴다   APP0 (JFIF)            APP2 중 ICC_PROFILE (색 프로필 — 빼면 색이 바랜다)
 *          APP14 (Adobe · 색 변환)  APPn 이 아닌 모든 마커 (DQT · DHT · SOF · SOS · DRI …)
 * 버린다   APP1 (Exif · XMP)      APP13 (IPTC)     COM (주석)    그 밖의 APPn
 *          EOI 뒤의 모든 바이트 (제조사 트레일러 · 모션 포토 영상 — 영상에도 위치가 실린다)
 * </pre>
 *
 * <p><b>영상 데이터를 해석하지 않는다.</b> 스캔 구간은 바이트 그대로 옮기고, 그 안의 마커 경계만 따라간다 — 픽셀은 한 바이트도 안 바뀐다. 프로그레시브 JPEG
 * 은 스캔 사이에 마커가 다시 오므로 첫 SOS 뒤도 끝까지 걷는다.
 */
final class JpegMetadata {

  private static final int SOI = 0xD8;
  private static final int EOI = 0xD9;
  private static final int SOS = 0xDA;
  private static final int APP0 = 0xE0;
  private static final int APP1 = 0xE1;
  private static final int APP2 = 0xE2;
  private static final int APP14 = 0xEE;
  private static final int APP15 = 0xEF;
  private static final int COM = 0xFE;

  private static final byte[] EXIF_HEADER = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] ICC_HEADER = "ICC_PROFILE\0".getBytes(StandardCharsets.ISO_8859_1);

  private JpegMetadata() {}

  static byte[] strip(byte[] in) throws UnsupportedImageException {
    if (in.length < 4 || (in[0] & 0xFF) != 0xFF || (in[1] & 0xFF) != SOI) {
      throw new UnsupportedImageException("JPEG 시작 마커가 없다");
    }

    OptionalInt orientation = findOrientation(in);
    ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
    out.write(0xFF);
    out.write(SOI);

    int pos = 2;
    boolean orientationWritten = orientation.isEmpty();
    boolean first = true;

    while (pos < in.length) {
      if ((in[pos] & 0xFF) != 0xFF) {
        throw new UnsupportedImageException("마커 자리에 마커가 없다");
      }
      while (pos < in.length && (in[pos] & 0xFF) == 0xFF) {
        pos++; // 채움 바이트
      }
      if (pos >= in.length) {
        throw new UnsupportedImageException("마커가 끝에서 잘렸다");
      }
      int marker = in[pos++] & 0xFF;

      if (marker == EOI) {
        writeOrientationIfPending(out, orientation, orientationWritten);
        out.write(0xFF);
        out.write(EOI);
        return out.toByteArray(); // EOI 뒤는 버린다
      }

      if (isStandalone(marker)) {
        out.write(0xFF);
        out.write(marker);
        continue;
      }

      if (pos + 2 > in.length) {
        throw new UnsupportedImageException("세그먼트 길이가 잘렸다");
      }
      int length = (in[pos] & 0xFF) << 8 | in[pos + 1] & 0xFF;
      if (length < 2 || pos + length > in.length) {
        throw new UnsupportedImageException("세그먼트 길이가 파일 밖을 가리킨다");
      }

      // 방향 APP1 은 SOI 바로 뒤에 둔다. 첫 세그먼트가 JFIF(APP0)면 JFIF 규격대로 그 뒤다.
      if (!orientationWritten && !(first && marker == APP0)) {
        writeOrientationIfPending(out, orientation, false);
        orientationWritten = true;
      }
      first = false;

      if (keeps(marker, in, pos + 2, length - 2)) {
        out.write(0xFF);
        out.write(marker);
        out.write(in, pos, length);
      }
      pos += length;

      if (marker == SOS) {
        pos = copyScan(in, pos, out);
      }
    }

    throw new UnsupportedImageException("EOI 가 없다");
  }

  /** 스캔 데이터를 다음 마커 직전까지 그대로 옮긴다. {@code FF 00}(바이트 스터핑)과 {@code FF D0~D7}(RST)은 데이터다. */
  private static int copyScan(byte[] in, int pos, ByteArrayOutputStream out) {
    int start = pos;
    while (pos + 1 < in.length) {
      if ((in[pos] & 0xFF) == 0xFF) {
        int next = in[pos + 1] & 0xFF;
        if (next != 0x00 && !(next >= 0xD0 && next <= 0xD7) && next != 0xFF) {
          break;
        }
      }
      pos++;
    }
    out.write(in, start, pos - start);
    return pos;
  }

  private static boolean keeps(int marker, byte[] in, int payload, int payloadLength) {
    if (marker == APP0 || marker == APP14) {
      return true;
    }
    if (marker == APP2) {
      return startsWith(in, payload, payloadLength, ICC_HEADER);
    }
    if (marker >= APP0 && marker <= APP15) {
      return false; // APP1 · APP13 · 제조사 APPn
    }
    return marker != COM;
  }

  private static boolean isStandalone(int marker) {
    return marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7);
  }

  /** 첫 SOS 전의 Exif APP1 에서 방향을 읽는다. 없으면 빈 값이다. */
  private static OptionalInt findOrientation(byte[] in) {
    int pos = 2;
    while (pos + 4 <= in.length) {
      if ((in[pos] & 0xFF) != 0xFF) {
        return OptionalInt.empty();
      }
      int marker = in[pos + 1] & 0xFF;
      if (marker == SOS || marker == EOI) {
        return OptionalInt.empty();
      }
      if (marker == 0xFF || isStandalone(marker)) {
        pos += marker == 0xFF ? 1 : 2;
        continue;
      }
      int length = (in[pos + 2] & 0xFF) << 8 | in[pos + 3] & 0xFF;
      int payload = pos + 4;
      if (length < 2 || payload + length - 2 > in.length) {
        return OptionalInt.empty();
      }
      if (marker == APP1 && startsWith(in, payload, length - 2, EXIF_HEADER)) {
        return TiffOrientation.read(
            in, payload + EXIF_HEADER.length, length - 2 - EXIF_HEADER.length);
      }
      pos += 2 + length;
    }
    return OptionalInt.empty();
  }

  private static void writeOrientationIfPending(
      ByteArrayOutputStream out, OptionalInt orientation, boolean alreadyWritten) {
    if (alreadyWritten || orientation.isEmpty()) {
      return;
    }
    byte[] tiff = TiffOrientation.orientationOnly(orientation.getAsInt());
    int length = 2 + EXIF_HEADER.length + tiff.length;
    out.write(0xFF);
    out.write(APP1);
    out.write(length >> 8);
    out.write(length & 0xFF);
    out.write(EXIF_HEADER, 0, EXIF_HEADER.length);
    out.write(tiff, 0, tiff.length);
  }

  private static boolean startsWith(byte[] in, int offset, int length, byte[] prefix) {
    if (length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (in[offset + i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
