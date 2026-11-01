package io.privsnap.app;

public final class PrivSnapTests {

    private static int passed = 0;

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("FAILED: " + msg);
        }
        passed++;
    }

    public static void main(String[] args) {
        // 1. Minimal valid JPEG stream (SOI + APP0 + DQT + SOF0 + SOS + EOI)
        byte[] minimalJpeg = new byte[]{
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x01, 0x00, 0x48, 0x00, 0x48, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00, 0x11, 0x22,
                (byte) 0xFF, (byte) 0xD9
        };

        // 2. Inject real binary EXIF + GPS into JPEG
        byte[] withExif = ExifInspector.injectDemoExif(minimalJpeg);
        check(withExif.length > minimalJpeg.length + 100, "Injected EXIF JPEG should contain binary APP1 payload");

        ExifInspector.Report reportDirty = ExifInspector.inspect(withExif, "IMG_20261031_142809.jpg");
        check(reportDirty.hasGps, "Should detect embedded GPS coordinates");
        check(reportDirty.gpsDisplay.contains("37.77493") && reportDirty.gpsDisplay.contains("122.41942"),
                "Should decode exact rational DMS GPS coordinates: got " + reportDirty.gpsDisplay);
        check(reportDirty.gpsDisplay.contains("18.5m"),
                "Should decode GPS altitude: got " + reportDirty.gpsDisplay);
        check(reportDirty.deviceDisplay.contains("Apple") && reportDirty.deviceDisplay.contains("iPhone 16 Pro"),
                "Should decode Make and Model: got " + reportDirty.deviceDisplay);
        check("2026:10:31 14:28:09".equals(reportDirty.dateTimeDisplay),
                "Should decode DateTimeOriginal: got " + reportDirty.dateTimeDisplay);
        check(reportDirty.lensSerialDisplay.contains("24mm") && reportDirty.lensSerialDisplay.contains("F2L984201US"),
                "Should decode LensModel and BodySerialNumber: got " + reportDirty.lensSerialDisplay);
        check(reportDirty.fileNameLeaks, "Should detect leaky camera filename");
        check(!reportDirty.isCompletelyClean(), "Dirty report must not be marked clean");
        check(reportDirty.riskScore() >= 80, "Dirty photo with GPS should have high risk score");

        // 3. Strip all metadata segments and generate anonymous filename
        byte[] scrubbed = ExifInspector.stripJpegMetadata(withExif);
        String cleanName = PixelScrubber.generateCleanFileName("jpg");
        check(cleanName.startsWith("PS_") && cleanName.endsWith(".jpg") && cleanName.length() == 15,
                "Generated clean filename should match PS_<8hex>.jpg: got " + cleanName);

        ExifInspector.Report reportClean = ExifInspector.inspect(scrubbed, cleanName);
        check(!reportClean.hasGps, "Scrubbed JPEG must have 0 GPS data");
        check(reportClean.deviceDisplay.isEmpty(), "Scrubbed JPEG must have 0 device make/model");
        check(reportClean.dateTimeDisplay.isEmpty(), "Scrubbed JPEG must have 0 timestamp");
        check(reportClean.metadataSegmentsCount == 0, "Scrubbed JPEG must have 0 metadata segments");
        check(!reportClean.fileNameLeaks, "Anonymous filename must not trigger filename leak warning");
        check(reportClean.isCompletelyClean(), "Scrubbed JPEG + anonymous filename must be 100% clean");
        check(reportClean.riskScore() == 0, "Scrubbed JPEG risk score must be 0");

        // 4. Verify PNG metadata inspection (tEXt / eXIf chunk detection)
        byte[] pngWithText = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0C, 't', 'E', 'X', 't',
                'S', 'o', 'f', 't', 'w', 'a', 'r', 'e', 0x00, 'A', 'p', 'p',
                0x00, 0x00, 0x00, 0x00, // CRC placeholder
                0x00, 0x00, 0x00, 0x00, 'I', 'E', 'N', 'D', 0x00, 0x00, 0x00, 0x00
        };
        ExifInspector.Report pngReport = ExifInspector.inspect(pngWithText, "Screenshot_20261031_wechat.png");
        check(pngReport.metadataSegmentsCount == 1, "Should detect PNG tEXt chunk");
        check(pngReport.softwareDisplay.contains("Software:App"), "Should parse PNG tEXt content");
        check(pngReport.fileNameLeaks, "Should detect WeChat screenshot filename leak");

        // 5. Verify Bilingual I18n toggling
        I18n.setMode(null, I18n.LANG_EN);
        check(I18n.isEn(), "LANG_EN should activate English mode");
        check("Clean".equals(I18n.tr("净图", "Clean")), "English translation should be returned in EN mode");

        I18n.setMode(null, I18n.LANG_ZH);
        check(!I18n.isEn(), "LANG_ZH should activate Chinese mode");
        check("净图".equals(I18n.tr("净图", "Clean")), "Chinese translation should be returned in ZH mode");

        System.out.println("PASS: All " + passed + " PrivSnap core assertions succeeded.");
    }
}
