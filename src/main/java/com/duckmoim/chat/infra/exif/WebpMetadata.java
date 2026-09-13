package com.duckmoim.chat.infra.exif;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.OptionalInt;
import java.util.Set;

/**
 * WEBP 의 메타데이터 청크를 걷어낸다 (CH-16).
 *
 * <p>WEBP 는 RIFF 컨테이너다. <b>메타데이터는 확장 형식({@code VP8X})에만 올 수 있고</b>, {@code EXIF} · {@code XMP } 청크로
 * 실린다. 단순 형식({@code VP8 } · {@code VP8L} 하나)에는 실을 자리가 없어 그대로 둔다.
 *
 * <pre>
 * 남긴다   VP8X · ICCP · ANIM · ANMF · ALPH · VP8 · VP8L
 * 버린다   EXIF · XMP · 모르는 청크
 * 고친다   VP8X 의 EXIF(0x08) · XMP(0x04) 플래그   RIFF 전체 크기
 * </pre>
 *
 * <p><b>방향을 남기면 {@code EXIF} 청크를 끝에 새로 붙이고 그 플래그를 다시 켠다.</b> 규격의 청크 순서가 영상 데이터 뒤에 EXIF 다.
 */
final class WebpMetadata {

  private static final int FLAG_EXIF = 0x08;
  private static final int FLAG_XMP = 0x04;
  private static final byte[] EXIF_HEADER = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);

  private static final Set<String> KEPT =
      Set.of("VP8X", "ICCP", "ANIM", "ANMF", "ALPH", "VP8 ", "VP8L");

  private WebpMetadata() {}

  static boolean matches(byte[] in) {
    return in.length >= 12
        && in[0] == 'R'
        && in[1] == 'I'
        && in[2] == 'F'
        && in[3] == 'F'
        && in[8] == 'W'
        && in[9] == 'E'
        && in[10] == 'B'
        && in[11] == 'P';
  }

  static byte[] strip(byte[] in) throws UnsupportedImageException {
    long riffSize = u32le(in, 4);
    if (riffSize + 8 > in.length || riffSize < 4) {
      throw new UnsupportedImageException("RIFF 크기가 파일 밖을 가리킨다");
    }
    int end = (int) riffSize + 8;

    OptionalInt orientation = OptionalInt.empty();
    ByteArrayOutputStream body = new ByteArrayOutputStream(in.length);
    int vp8xFlagsAt = -1;

    int pos = 12;
    while (pos < end) {
      if (pos + 8 > end) {
        throw new UnsupportedImageException("청크 머리가 잘렸다");
      }
      String type = new String(in, pos, 4, StandardCharsets.ISO_8859_1);
      long size = u32le(in, pos + 4);
      long padded = size + (size & 1);
      if (pos + 8 + padded > end && pos + 8 + size != end) {
        throw new UnsupportedImageException("청크 길이가 파일 밖을 가리킨다");
      }
      int total = (int) Math.min(8 + padded, end - pos);

      if ("EXIF".equals(type) && orientation.isEmpty()) {
        orientation = readOrientation(in, pos + 8, (int) size);
      }
      if (KEPT.contains(type)) {
        if ("VP8X".equals(type)) {
          if (size < 10) {
            throw new UnsupportedImageException("VP8X 가 짧다");
          }
          vp8xFlagsAt = body.size() + 8;
        }
        body.write(in, pos, total);
      }
      pos += total;
    }

    byte[] chunks = body.toByteArray();
    if (vp8xFlagsAt >= 0) {
      int flags = chunks[vp8xFlagsAt] & 0xFF & ~(FLAG_EXIF | FLAG_XMP);
      if (orientation.isPresent()) {
        flags |= FLAG_EXIF;
      }
      chunks[vp8xFlagsAt] = (byte) flags;
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
    out.write(in, 0, 12); // RIFF · 크기(아래에서 고친다) · WEBP
    out.write(chunks, 0, chunks.length);
    if (vp8xFlagsAt >= 0 && orientation.isPresent()) {
      byte[] tiff = TiffOrientation.orientationOnly(orientation.getAsInt());
      out.write("EXIF".getBytes(StandardCharsets.ISO_8859_1), 0, 4);
      writeU32le(out, tiff.length);
      out.write(tiff, 0, tiff.length);
      if ((tiff.length & 1) == 1) {
        out.write(0);
      }
    }

    byte[] result = out.toByteArray();
    int size = result.length - 8;
    result[4] = (byte) size;
    result[5] = (byte) (size >>> 8);
    result[6] = (byte) (size >>> 16);
    result[7] = (byte) (size >>> 24);
    return result;
  }

  /** WEBP 의 EXIF 청크는 {@code Exif\0\0} 머리를 붙이는 기록기와 안 붙이는 기록기가 섞여 있다. 둘 다 받는다. */
  private static OptionalInt readOrientation(byte[] in, int offset, int length) {
    if (length >= EXIF_HEADER.length
        && Arrays.equals(
            in, offset, offset + EXIF_HEADER.length, EXIF_HEADER, 0, EXIF_HEADER.length)) {
      return TiffOrientation.read(in, offset + EXIF_HEADER.length, length - EXIF_HEADER.length);
    }
    return TiffOrientation.read(in, offset, length);
  }

  private static long u32le(byte[] b, int i) {
    return b[i] & 0xFFL
        | (b[i + 1] & 0xFFL) << 8
        | (b[i + 2] & 0xFFL) << 16
        | (b[i + 3] & 0xFFL) << 24;
  }

  private static void writeU32le(ByteArrayOutputStream out, int value) {
    out.write(value & 0xFF);
    out.write(value >>> 8 & 0xFF);
    out.write(value >>> 16 & 0xFF);
    out.write(value >>> 24 & 0xFF);
  }
}
