package com.duckmoim.chat.infra.exif;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * PNG 의 메타데이터 청크를 걷어낸다 (CH-16).
 *
 * <p><b>필수 청크는 전부, 보조 청크는 렌더링에 쓰이는 것만 남긴다.</b> 필수 청크(이름 첫 글자가 대문자)는 모르는 것이라도 빼면 그림이 깨지고, 모르는 필수 청크가
 * 있으면 디코더가 어차피 거부한다.
 *
 * <pre>
 * 버린다   eXIf (EXIF)   tEXt · zTXt · iTXt (텍스트 — XMP 에 좌표가 실린다)   tIME   모르는 보조 청크
 * </pre>
 *
 * <p><b>남기는 청크는 CRC 까지 바이트 그대로 옮긴다.</b> 새로 쓰는 것은 방향 한 칸짜리 {@code eXIf} 하나이고 그 CRC 만 계산한다. 규격상
 * {@code eXIf} 는 {@code IDAT} 앞이라 {@code IHDR} 바로 뒤에 둔다.
 */
final class PngMetadata {

  static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

  /** 렌더링 · 애니메이션에 쓰이는 보조 청크. 이 밖의 보조 청크는 버린다. */
  private static final Set<String> KEPT_ANCILLARY =
      Set.of(
          "tRNS", "cHRM", "gAMA", "iCCP", "sBIT", "sRGB", "cICP", "mDCV", "mDCv", "cLLI", "cLLi",
          "bKGD", "hIST", "pHYs", "sPLT", "acTL", "fcTL", "fdAT");

  private PngMetadata() {}

  static byte[] strip(byte[] in) throws UnsupportedImageException {
    OptionalInt orientation = findOrientation(in);
    ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
    out.write(SIGNATURE, 0, SIGNATURE.length);

    int pos = SIGNATURE.length;
    boolean sawEnd = false;
    while (pos < in.length) {
      if (pos + 12 > in.length) {
        throw new UnsupportedImageException("청크 머리가 잘렸다");
      }
      long length = u32(in, pos);
      if (length > Integer.MAX_VALUE || pos + 12 + length > in.length) {
        throw new UnsupportedImageException("청크 길이가 파일 밖을 가리킨다");
      }
      String type = new String(in, pos + 4, 4, StandardCharsets.ISO_8859_1);
      int total = 12 + (int) length;

      if (keeps(type)) {
        out.write(in, pos, total);
      }
      if ("IHDR".equals(type) && orientation.isPresent()) {
        writeExif(out, TiffOrientation.orientationOnly(orientation.getAsInt()));
      }
      pos += total;

      if ("IEND".equals(type)) {
        sawEnd = true;
        break; // IEND 뒤는 버린다
      }
    }

    if (!sawEnd) {
      throw new UnsupportedImageException("IEND 가 없다");
    }
    return out.toByteArray();
  }

  private static boolean keeps(String type) {
    boolean critical = Character.isUpperCase(type.charAt(0));
    return critical || KEPT_ANCILLARY.contains(type);
  }

  private static OptionalInt findOrientation(byte[] in) {
    int pos = SIGNATURE.length;
    while (pos + 12 <= in.length) {
      long length = u32(in, pos);
      if (length > Integer.MAX_VALUE || pos + 12 + length > in.length) {
        return OptionalInt.empty();
      }
      String type = new String(in, pos + 4, 4, StandardCharsets.ISO_8859_1);
      if ("eXIf".equals(type)) {
        return TiffOrientation.read(in, pos + 8, (int) length);
      }
      if ("IDAT".equals(type) || "IEND".equals(type)) {
        return OptionalInt.empty();
      }
      pos += 12 + (int) length;
    }
    return OptionalInt.empty();
  }

  private static void writeExif(ByteArrayOutputStream out, byte[] tiff) {
    byte[] type = "eXIf".getBytes(StandardCharsets.ISO_8859_1);
    CRC32 crc = new CRC32();
    crc.update(type);
    crc.update(tiff);

    writeU32(out, tiff.length);
    out.write(type, 0, type.length);
    out.write(tiff, 0, tiff.length);
    writeU32(out, crc.getValue());
  }

  private static long u32(byte[] b, int i) {
    return (b[i] & 0xFFL) << 24
        | (b[i + 1] & 0xFFL) << 16
        | (b[i + 2] & 0xFFL) << 8
        | b[i + 3] & 0xFFL;
  }

  private static void writeU32(ByteArrayOutputStream out, long value) {
    out.write((int) (value >>> 24) & 0xFF);
    out.write((int) (value >>> 16) & 0xFF);
    out.write((int) (value >>> 8) & 0xFF);
    out.write((int) value & 0xFF);
  }
}
