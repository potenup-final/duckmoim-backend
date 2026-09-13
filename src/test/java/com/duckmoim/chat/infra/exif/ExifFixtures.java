package com.duckmoim.chat.infra.exif;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/**
 * 좌표가 든 사진을 만든다 — 검증 기준 「좌표가 든 사진 업로드 후 저장된 파일에 EXIF 없음」의 앞 절반.
 *
 * <p><b>exiftool 이 없어도 돈다.</b> JPEG · PNG 는 {@code ImageIO} 로 진짜 그림을 만들고 그 안에 EXIF 를 끼워 넣는다. WEBP 는
 * JVM 에 인코더가 없어 알려진 1×1 무손실 파일의 비트스트림을 쓴다 — 벗기는 쪽이 비트스트림을 해석하지 않으므로 이것으로 충분하다.
 *
 * <p><b>새어 나가면 바로 보이도록 흔한 값이 아닌 것을 심는다.</b> 검사는 이 값들의 바이트가 결과에 <b>한 번도 나오지 않는지</b>로 판정한다 — 벗기는 코드의
 * 파서로 확인하면 같은 착각을 두 번 하게 된다.
 */
public final class ExifFixtures {

  public static final String MAKE = "LeakyPhone";
  public static final String DATE_TIME = "2026:09:12 21:34:07";

  /** GPS 위도 초의 분자. 리틀엔디언 바이트 {@code 0D F0 AD 0B} 가 결과에 있으면 좌표가 샌 것이다. */
  public static final int GPS_SECONDS_NUMERATOR = 0x0BADF00D;

  public static final String XMP_SECRET = "LeakyXmpLatitude37.5665";
  public static final String IPTC_SECRET = "LeakyIptcCity";
  public static final String COMMENT_SECRET = "LeakyComment";
  public static final String TEXT_SECRET = "LeakyPngText";
  public static final String VENDOR_SECRET = "LeakyVendorChunk";
  public static final String TRAILER_SECRET = "LeakyTrailerAfterEnd";

  /** 방향만 담은 블록의 기대값. 벗기는 코드의 상수를 가져오지 않고 규격대로 손으로 적는다. */
  public static byte[] orientationOnlyTiff(int orientation) {
    return new byte[] {
      'I',
      'I',
      42,
      0,
      8,
      0,
      0,
      0,
      1,
      0,
      0x12,
      0x01,
      3,
      0,
      1,
      0,
      0,
      0,
      (byte) orientation,
      0,
      0,
      0,
      0,
      0,
      0,
      0
    };
  }

  private ExifFixtures() {}

  /**
   * IFD0(기종 · 방향 · 촬영 시각 · GPS 포인터) + GPS IFD(위도 참조 · 위도)를 가진 리틀엔디언 TIFF 블록.
   *
   * @param orientation 1~8. 0 이면 방향 태그를 넣지 않는다
   */
  static byte[] tiffWithGps(int orientation) {
    ByteBuffer b = ByteBuffer.allocate(160).order(ByteOrder.LITTLE_ENDIAN);
    int entries = orientation == 0 ? 3 : 4;
    int ifd0 = 8;
    int ifd0End = ifd0 + 2 + entries * 12 + 4;
    int makeAt = ifd0End;
    int dateAt = makeAt + 12;
    int gpsAt = dateAt + 20;
    int ratAt = gpsAt + 2 + 2 * 12 + 4;

    b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifd0);
    b.position(ifd0);
    b.putShort((short) entries);
    entry(b, 0x010F, 2, MAKE.length() + 1, makeAt);
    if (orientation != 0) {
      b.putShort((short) 0x0112)
          .putShort((short) 3)
          .putInt(1)
          .putShort((short) orientation)
          .putShort((short) 0);
    }
    entry(b, 0x0132, 2, DATE_TIME.length() + 1, dateAt);
    entry(b, 0x8825, 4, 1, gpsAt);
    b.putInt(0);

    b.position(makeAt);
    b.put((MAKE + "\0").getBytes(StandardCharsets.US_ASCII));
    b.position(dateAt);
    b.put((DATE_TIME + "\0").getBytes(StandardCharsets.US_ASCII));

    b.position(gpsAt);
    b.putShort((short) 2);
    b.putShort((short) 0x0001)
        .putShort((short) 2)
        .putInt(2)
        .put((byte) 'N')
        .put((byte) 0)
        .putShort((short) 0);
    entry(b, 0x0002, 5, 3, ratAt);
    b.putInt(0);

    b.position(ratAt);
    b.putInt(37).putInt(1).putInt(34).putInt(1).putInt(GPS_SECONDS_NUMERATOR).putInt(1000);

    byte[] out = new byte[b.position()];
    System.arraycopy(b.array(), 0, out, 0, out.length);
    return out;
  }

  private static void entry(ByteBuffer b, int tag, int type, int count, int valueOrOffset) {
    b.putShort((short) tag).putShort((short) type).putInt(count).putInt(valueOrOffset);
  }

  // ── JPEG ───────────────────────────────────────────────────────────────

  /** 진짜 JPEG 에 Exif · XMP · IPTC · 주석 · ICC · 트레일러를 심는다. */
  public static byte[] jpegWithGps(int orientation) {
    byte[] plain = encode("jpg");
    int afterApp0 = afterFirstSegment(plain);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(plain, 0, afterApp0);
    segment(
        out,
        0xE1,
        concat("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), tiffWithGps(orientation)));
    segment(
        out,
        0xE1,
        ascii("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta>" + XMP_SECRET + "</x:xmpmeta>"));
    segment(out, 0xED, ascii("Photoshop 3.0\0" + IPTC_SECRET));
    segment(out, 0xFE, ascii(COMMENT_SECRET));
    segment(out, 0xE2, ascii("ICC_PROFILE\0\1\1FAKEPROFILE"));
    out.write(plain, afterApp0, plain.length - afterApp0);
    out.writeBytes(ascii(TRAILER_SECRET));
    return out.toByteArray();
  }

  private static int afterFirstSegment(byte[] jpeg) {
    int length = (jpeg[4] & 0xFF) << 8 | jpeg[5] & 0xFF;
    return 4 + length;
  }

  private static void segment(ByteArrayOutputStream out, int marker, byte[] payload) {
    int length = payload.length + 2;
    out.write(0xFF);
    out.write(marker);
    out.write(length >> 8);
    out.write(length & 0xFF);
    out.writeBytes(payload);
  }

  // ── PNG ────────────────────────────────────────────────────────────────

  /** 진짜 PNG 의 IHDR 뒤에 eXIf · 텍스트 청크 · 모르는 보조 청크를 심고, 렌더링 청크 gAMA 를 함께 넣는다. */
  public static byte[] pngWithGps(int orientation) {
    byte[] plain = encode("png");
    int afterIhdr = 8 + 12 + 13;

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(plain, 0, afterIhdr);
    chunk(out, "eXIf", tiffWithGps(orientation));
    chunk(out, "gAMA", new byte[] {0, 0, (byte) 0xB1, (byte) 0x8F});
    chunk(out, "tEXt", ascii("Comment\0" + TEXT_SECRET));
    chunk(
        out, "iTXt", ascii("XML:com.adobe.xmp\0\0\0\0\0<x:xmpmeta>" + XMP_SECRET + "</x:xmpmeta>"));
    chunk(out, "tIME", new byte[] {0x07, (byte) 0xEA, 9, 12, 21, 34, 7});
    chunk(out, "leAk", ascii(VENDOR_SECRET));
    out.write(plain, afterIhdr, plain.length - afterIhdr);
    out.writeBytes(ascii(TRAILER_SECRET));
    return out.toByteArray();
  }

  static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
    byte[] typeBytes = ascii(type);
    CRC32 crc = new CRC32();
    crc.update(typeBytes);
    crc.update(data);
    out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
    out.writeBytes(typeBytes);
    out.writeBytes(data);
    out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
  }

  // ── WEBP ───────────────────────────────────────────────────────────────

  /** 알려진 1×1 무손실 WEBP 의 VP8L 비트스트림 (13바이트 · 홀수라 패딩이 붙는다). */
  static final byte[] VP8L_PAYLOAD = {
    0x2F, 0, 0, 0, 0x10, 0x07, 0x10, 0x11, 0x11, (byte) 0x88, (byte) 0x88, (byte) 0xFE, 0x07
  };

  /** 확장 형식 WEBP — VP8X(EXIF · XMP 플래그) · VP8L · EXIF(Exif\0\0 머리 포함) · XMP · 모르는 청크. */
  static byte[] webpWithGps(int orientation) {
    ByteArrayOutputStream chunks = new ByteArrayOutputStream();
    riffChunk(chunks, "VP8X", new byte[] {0x08 | 0x04, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    riffChunk(chunks, "VP8L", VP8L_PAYLOAD);
    riffChunk(
        chunks,
        "EXIF",
        concat("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), tiffWithGps(orientation)));
    riffChunk(chunks, "XMP ", ascii("<x:xmpmeta>" + XMP_SECRET + "</x:xmpmeta>"));
    riffChunk(chunks, "LEAK", ascii(VENDOR_SECRET));
    return riff(chunks.toByteArray());
  }

  /** 단순 형식 WEBP — VP8L 하나. 메타데이터를 실을 자리가 없다. */
  static byte[] simpleWebp() {
    ByteArrayOutputStream chunks = new ByteArrayOutputStream();
    riffChunk(chunks, "VP8L", VP8L_PAYLOAD);
    return riff(chunks.toByteArray());
  }

  private static byte[] riff(byte[] chunks) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(ascii("RIFF"));
    out.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunks.length + 4).array());
    out.writeBytes(ascii("WEBP"));
    out.writeBytes(chunks);
    return out.toByteArray();
  }

  private static void riffChunk(ByteArrayOutputStream out, String type, byte[] data) {
    out.writeBytes(ascii(type));
    out.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).array());
    out.writeBytes(data);
    if ((data.length & 1) == 1) {
      out.write(0);
    }
  }

  // ── 공용 ───────────────────────────────────────────────────────────────

  private static byte[] encode(String format) {
    BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 8; y++) {
      for (int x = 0; x < 8; x++) {
        image.setRGB(x, y, (x * 31) << 16 | (y * 31) << 8 | 0x80);
      }
    }
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, format, out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.ISO_8859_1);
  }

  static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
