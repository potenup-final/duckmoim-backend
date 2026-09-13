package com.duckmoim.chat.infra.exif;

import static com.duckmoim.chat.infra.exif.ExifFixtures.COMMENT_SECRET;
import static com.duckmoim.chat.infra.exif.ExifFixtures.DATE_TIME;
import static com.duckmoim.chat.infra.exif.ExifFixtures.GPS_SECONDS_NUMERATOR;
import static com.duckmoim.chat.infra.exif.ExifFixtures.IPTC_SECRET;
import static com.duckmoim.chat.infra.exif.ExifFixtures.MAKE;
import static com.duckmoim.chat.infra.exif.ExifFixtures.TEXT_SECRET;
import static com.duckmoim.chat.infra.exif.ExifFixtures.TRAILER_SECRET;
import static com.duckmoim.chat.infra.exif.ExifFixtures.VENDOR_SECRET;
import static com.duckmoim.chat.infra.exif.ExifFixtures.XMP_SECRET;
import static com.duckmoim.chat.infra.exif.ExifFixtures.ascii;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * <b>이 검사가 CH-16 의 검증 기준이다</b> — 좌표가 든 사진 업로드 후 저장된 파일에 EXIF 없음.
 *
 * <p><b>새었는지를 벗기는 코드의 파서로 확인하지 않는다.</b> 같은 착각을 두 번 하게 된다. 픽스처에 흔하지 않은 값(기종 · 촬영 시각 · GPS 분자 · XMP ·
 * IPTC 문자열)을 심고, <b>그 바이트가 결과에 한 번도 안 나오는지</b> 본다.
 *
 * <p><b>남겨야 할 것도 함께 본다.</b> 좌표를 지우는 김에 그림을 망가뜨리면 검증 기준은 통과해도 기능이 죽는다 — 픽셀 데이터 · 색 프로필 · 방향이 그대로인지,
 * 파일이 여전히 열리는지 확인한다.
 */
@DisplayName("사진 메타데이터 제거")
class ImageMetadataStripperTest {

  private static final int ORIENTATION_PORTRAIT = 6;

  private final ImageMetadataStripper stripper = new ImageMetadataStripper();

  @Nested
  @DisplayName("JPEG")
  class Jpeg {

    /** <b>검증 기준.</b> 좌표 · 촬영 시각 · 기종 · XMP · IPTC · 주석 · EOI 뒤 트레일러가 전부 사라진다. */
    @DisplayName("좌표를 비롯한 위치·식별 정보가 한 바이트도 남지 않는다.")
    @Test
    void strip_removesEveryPlantedSecret() throws Exception {
      byte[] out = stripper.strip(ExifFixtures.jpegWithGps(ORIENTATION_PORTRAIT));

      assertNoSecrets(out, COMMENT_SECRET, IPTC_SECRET, XMP_SECRET, TRAILER_SECRET);
    }

    /** 영상 데이터를 해석하지 않고 옮긴다. SOS 부터 EOI 까지가 바이트 그대로여야 화질이 안 깎인 것이다. */
    @DisplayName("스캔 데이터는 바이트 그대로다.")
    @Test
    void strip_keepsScanDataVerbatim() throws Exception {
      byte[] in = ExifFixtures.jpegWithGps(ORIENTATION_PORTRAIT);
      byte[] out = stripper.strip(in);

      assertThat(scan(out)).isEqualTo(scan(in));
    }

    @DisplayName("벗긴 파일이 여전히 같은 크기의 그림으로 열린다.")
    @Test
    void strip_stillDecodes() throws Exception {
      byte[] out = stripper.strip(ExifFixtures.jpegWithGps(ORIENTATION_PORTRAIT));

      BufferedImage image = ImageIO.read(new ByteArrayInputStream(out));
      assertThat(image).isNotNull();
      assertThat(image.getWidth()).isEqualTo(8);
    }

    /** 색 프로필을 빼면 색이 바랜다. 남길 것을 적는 방식이라 목록에서 빠지면 여기서 걸린다. */
    @DisplayName("색 프로필(ICC)은 남긴다.")
    @Test
    void strip_keepsIccProfile() throws Exception {
      byte[] out = stripper.strip(ExifFixtures.jpegWithGps(ORIENTATION_PORTRAIT));

      assertThat(indexOf(out, ascii("ICC_PROFILE\0"))).isNotNegative();
    }

    /**
     * <b>방향 한 칸만 남는다.</b> 전부 지우면 세로로 찍은 사진이 옆으로 누워서 뜬다.
     *
     * <p>남은 Exif 블록이 <b>규격대로 손으로 적은 26바이트와 똑같은지</b> 본다 — 태그가 하나라도 더 붙어 있으면 다르다.
     */
    @DisplayName("방향 태그 하나짜리 EXIF 만 남는다.")
    @Test
    void strip_keepsOnlyOrientation() throws Exception {
      byte[] out = stripper.strip(ExifFixtures.jpegWithGps(ORIENTATION_PORTRAIT));

      byte[] expected =
          ExifFixtures.concat(
              ascii("Exif\0\0"), ExifFixtures.orientationOnlyTiff(ORIENTATION_PORTRAIT));
      assertThat(indexOf(out, expected)).isNotNegative();
      assertThat(countOf(out, ascii("Exif\0\0"))).isEqualTo(1);
    }

    /** 원본에 방향이 없으면 EXIF 를 아예 만들지 않는다. */
    @DisplayName("원본에 방향이 없으면 EXIF 가 하나도 남지 않는다.")
    @Test
    void strip_leavesNoExifWithoutOrientation() throws Exception {
      byte[] out = stripper.strip(ExifFixtures.jpegWithGps(0));

      assertThat(indexOf(out, ascii("Exif\0\0"))).isNegative();
      assertNoSecrets(out);
    }

    /** 재시도나 두 워커의 겹침이 안전한 이유다. 부르는 쪽은 결과가 같으면 덮어쓰기를 건너뛴다. */
    @DisplayName("두 번 벗겨도 결과가 같다.")
    @Test
    void strip_isIdempotent() throws Exception {
      byte[] once = stripper.strip(ExifFixtures.jpegWithGps(ORIENTATION_PORTRAIT));

      assertThat(stripper.strip(once)).isEqualTo(once);
    }

    @DisplayName("구조가 잘린 JPEG 은 벗길 수 없다고 답한다.")
    @Test
    void strip_rejectsTruncated() {
      byte[] in = ExifFixtures.jpegWithGps(ORIENTATION_PORTRAIT);

      assertThatThrownBy(() -> stripper.strip(Arrays.copyOf(in, 40)))
          .isInstanceOf(UnsupportedImageException.class);
    }

    private byte[] scan(byte[] jpeg) {
      int sos = indexOf(jpeg, new byte[] {(byte) 0xFF, (byte) 0xDA});
      int eoi = lastIndexOf(jpeg, new byte[] {(byte) 0xFF, (byte) 0xD9});
      return Arrays.copyOfRange(jpeg, sos, eoi + 2);
    }
  }

  @Nested
  @DisplayName("PNG")
  class Png {

    /** <b>검증 기준.</b> eXIf · 텍스트 청크(XMP 포함) · 모르는 보조 청크 · IEND 뒤 트레일러가 사라진다. */
    @DisplayName("좌표를 비롯한 위치·식별 정보가 한 바이트도 남지 않는다.")
    @Test
    void strip_removesEveryPlantedSecret() throws Exception {
      byte[] out = stripper.strip(ExifFixtures.pngWithGps(ORIENTATION_PORTRAIT));

      assertNoSecrets(out, TEXT_SECRET, XMP_SECRET, VENDOR_SECRET, TRAILER_SECRET);
    }

    @DisplayName("벗긴 파일이 여전히 같은 그림으로 열린다.")
    @Test
    void strip_stillDecodesToSamePixels() throws Exception {
      byte[] in = ExifFixtures.pngWithGps(ORIENTATION_PORTRAIT);
      byte[] out = stripper.strip(in);

      BufferedImage before = ImageIO.read(new ByteArrayInputStream(in));
      BufferedImage after = ImageIO.read(new ByteArrayInputStream(out));
      assertThat(after.getRGB(5, 3)).isEqualTo(before.getRGB(5, 3));
    }

    /** 새로 쓴 청크의 CRC 가 틀리면 브라우저가 그림을 거부한다. 모든 청크를 직접 다시 계산해 본다. */
    @DisplayName("모든 청크의 CRC 가 맞다.")
    @Test
    void strip_keepsEveryCrcValid() throws Exception {
      byte[] out = stripper.strip(ExifFixtures.pngWithGps(ORIENTATION_PORTRAIT));

      int pos = 8;
      while (pos < out.length) {
        int length = ByteBuffer.wrap(out, pos, 4).getInt();
        CRC32 crc = new CRC32();
        crc.update(out, pos + 4, 4 + length);
        int stored = ByteBuffer.wrap(out, pos + 8 + length, 4).getInt();
        assertThat((int) crc.getValue()).isEqualTo(stored);
        pos += 12 + length;
      }
      assertThat(pos).isEqualTo(out.length);
    }

    @DisplayName("렌더링 청크(gAMA)는 남긴다.")
    @Test
    void strip_keepsRenderingChunk() throws Exception {
      byte[] out = stripper.strip(ExifFixtures.pngWithGps(ORIENTATION_PORTRAIT));

      assertThat(indexOf(out, ascii("gAMA"))).isNotNegative();
    }

    @DisplayName("방향 태그 하나짜리 eXIf 만 남는다.")
    @Test
    void strip_keepsOnlyOrientation() throws Exception {
      byte[] out = stripper.strip(ExifFixtures.pngWithGps(ORIENTATION_PORTRAIT));

      byte[] expected =
          ExifFixtures.concat(
              ascii("eXIf"), ExifFixtures.orientationOnlyTiff(ORIENTATION_PORTRAIT));
      assertThat(indexOf(out, expected)).isNotNegative();
      assertThat(countOf(out, ascii("eXIf"))).isEqualTo(1);
    }

    @DisplayName("두 번 벗겨도 결과가 같다.")
    @Test
    void strip_isIdempotent() throws Exception {
      byte[] once = stripper.strip(ExifFixtures.pngWithGps(ORIENTATION_PORTRAIT));

      assertThat(stripper.strip(once)).isEqualTo(once);
    }
  }

  @Nested
  @DisplayName("WEBP")
  class Webp {

    /** <b>검증 기준.</b> EXIF · XMP · 모르는 청크가 사라진다. */
    @DisplayName("좌표를 비롯한 위치·식별 정보가 한 바이트도 남지 않는다.")
    @Test
    void strip_removesEveryPlantedSecret() throws Exception {
      byte[] out = stripper.strip(ExifFixtures.webpWithGps(ORIENTATION_PORTRAIT));

      assertNoSecrets(out, XMP_SECRET, VENDOR_SECRET);
    }

    @DisplayName("영상 청크(VP8L)는 바이트 그대로다.")
    @Test
    void strip_keepsBitstreamVerbatim() throws Exception {
      byte[] out = stripper.strip(ExifFixtures.webpWithGps(ORIENTATION_PORTRAIT));

      assertThat(
              indexOf(out, ExifFixtures.concat(ascii("VP8L\r\0\0\0"), ExifFixtures.VP8L_PAYLOAD)))
          .isNotNegative();
    }

    /** RIFF 크기가 틀리면 디코더가 파일 끝을 잘못 안다. */
    @DisplayName("RIFF 크기가 실제 길이와 맞는다.")
    @Test
    void strip_fixesRiffSize() throws Exception {
      byte[] out = stripper.strip(ExifFixtures.webpWithGps(ORIENTATION_PORTRAIT));

      int size = ByteBuffer.wrap(out, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
      assertThat(size).isEqualTo(out.length - 8);
    }

    /** 플래그가 실제 청크와 어긋나면 디코더가 없는 XMP 를 찾는다. XMP 는 끄고, 방향을 남겼으니 EXIF 는 켠다. */
    @DisplayName("VP8X 플래그가 남은 청크와 맞는다.")
    @Test
    void strip_fixesVp8xFlags() throws Exception {
      byte[] out = stripper.strip(ExifFixtures.webpWithGps(ORIENTATION_PORTRAIT));

      int flags = out[indexOf(out, ascii("VP8X")) + 8] & 0xFF;
      assertThat(flags & 0x04).as("XMP").isZero();
      assertThat(flags & 0x08).as("EXIF").isNotZero();
    }

    @DisplayName("방향 태그 하나짜리 EXIF 청크만 남는다.")
    @Test
    void strip_keepsOnlyOrientation() throws Exception {
      byte[] out = stripper.strip(ExifFixtures.webpWithGps(ORIENTATION_PORTRAIT));

      assertThat(indexOf(out, ExifFixtures.orientationOnlyTiff(ORIENTATION_PORTRAIT)))
          .isNotNegative();
      assertThat(countOf(out, ascii("EXIF"))).isEqualTo(1);
    }

    /** 단순 형식에는 메타데이터를 실을 자리가 없다. 손대지 않는다. */
    @DisplayName("단순 형식은 그대로 둔다.")
    @Test
    void strip_leavesSimpleFormatUntouched() throws Exception {
      byte[] in = ExifFixtures.simpleWebp();

      assertThat(stripper.strip(in)).isEqualTo(in);
    }

    @DisplayName("두 번 벗겨도 결과가 같다.")
    @Test
    void strip_isIdempotent() throws Exception {
      byte[] once = stripper.strip(ExifFixtures.webpWithGps(ORIENTATION_PORTRAIT));

      assertThat(stripper.strip(once)).isEqualTo(once);
    }
  }

  /**
   * <b>형식을 파일 앞 바이트로 판정한다.</b> {@code content_type} 은 클라이언트가 붙인 헤더라, 그것을 믿으면 「image/png」로 올린 다른 파일이
   * 형식별 처리를 빗나가 좌표가 그대로 남는다.
   */
  @DisplayName("JPEG · PNG · WEBP 가 아니면 벗길 수 없다고 답한다.")
  @Test
  void strip_rejectsUnknownFormat() {
    assertThatThrownBy(() -> stripper.strip(ascii("GIF89a....")))
        .isInstanceOf(UnsupportedImageException.class);
  }

  // ── 판정 도구 — 벗기는 코드와 무관하게 바이트로 본다 ────────────────────────

  private static void assertNoSecrets(byte[] out, String... extra) {
    assertThat(indexOf(out, ascii(MAKE))).as("기종").isNegative();
    assertThat(indexOf(out, ascii(DATE_TIME))).as("촬영 시각").isNegative();
    byte[] gps =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(GPS_SECONDS_NUMERATOR).array();
    assertThat(indexOf(out, gps)).as("GPS 좌표").isNegative();
    for (String secret : extra) {
      assertThat(indexOf(out, secret.getBytes(StandardCharsets.ISO_8859_1)))
          .as(secret)
          .isNegative();
    }
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static int lastIndexOf(byte[] haystack, byte[] needle) {
    for (int i = haystack.length - needle.length; i >= 0; i--) {
      if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
        return i;
      }
    }
    return -1;
  }

  private static int countOf(byte[] haystack, byte[] needle) {
    int count = 0;
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
        count++;
      }
    }
    return count;
  }
}
