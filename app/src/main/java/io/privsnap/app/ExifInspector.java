package io.privsnap.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ExifInspector {

    private static final Pattern LEAKY_FILENAME_PATTERN = Pattern.compile(
            "(?i).*(20\\d{6}|20\\d{2}[-_]?\\d{2}[-_]?\\d{2}|screenshot|img_|pxl_|dsc_|mvimg_|mmexport|wx_camera|wechat).*");

    public static final class Finding {
        public final String categoryZh;
        public final String categoryEn;
        public final String value;
        public final boolean highRisk;

        public Finding(String categoryZh, String categoryEn, String value, boolean highRisk) {
            this.categoryZh = categoryZh;
            this.categoryEn = categoryEn;
            this.value = value;
            this.highRisk = highRisk;
        }

        public String getCategory() {
            return I18n.tr(categoryZh, categoryEn);
        }
    }

    public static final class Report {
        public String format = "JPEG";
        public boolean hasGps = false;
        public String gpsDisplay = "";
        public String deviceDisplay = "";
        public String dateTimeDisplay = "";
        public String lensSerialDisplay = "";
        public String softwareDisplay = "";
        public int metadataSegmentsCount = 0;
        public int metadataTotalBytes = 0;
        public boolean fileNameLeaks = false;
        public String originalFileName = "";
        public final List<Finding> findings = new ArrayList<>();

        public boolean isCompletelyClean() {
            return !hasGps
                    && deviceDisplay.isEmpty()
                    && dateTimeDisplay.isEmpty()
                    && lensSerialDisplay.isEmpty()
                    && softwareDisplay.isEmpty()
                    && metadataSegmentsCount == 0
                    && !fileNameLeaks;
        }

        public int riskScore() {
            int score = 0;
            if (hasGps) score += 60;
            if (!deviceDisplay.isEmpty()) score += 15;
            if (!dateTimeDisplay.isEmpty()) score += 10;
            if (!lensSerialDisplay.isEmpty()) score += 10;
            if (metadataSegmentsCount > 0) score += 5;
            if (fileNameLeaks) score += 5;
            return Math.min(100, score);
        }
    }

    private ExifInspector() {}

    public static Report inspect(byte[] data, String fileName) {
        Report r = new Report();
        r.originalFileName = fileName != null ? fileName : "";
        if (!r.originalFileName.isEmpty() && LEAKY_FILENAME_PATTERN.matcher(r.originalFileName).matches()) {
            r.fileNameLeaks = true;
        }
        if (data == null || data.length < 4) {
            return r;
        }

        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            r.format = "JPEG";
            inspectJpeg(data, r);
        } else if (data.length >= 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            r.format = "PNG";
            inspectPng(data, r);
        } else if (data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            r.format = "WEBP";
            inspectWebp(data, r);
        }

        if (r.hasGps && !r.gpsDisplay.isEmpty()) {
            r.findings.add(new Finding("GPS 经纬度", "GPS Coordinates", r.gpsDisplay, true));
        }
        if (!r.deviceDisplay.isEmpty()) {
            r.findings.add(new Finding("拍摄设备", "Device Model", r.deviceDisplay, true));
        }
        if (!r.dateTimeDisplay.isEmpty()) {
            r.findings.add(new Finding("拍摄时间", "Capture Time", r.dateTimeDisplay, true));
        }
        if (!r.lensSerialDisplay.isEmpty()) {
            r.findings.add(new Finding("镜头与序列号", "Lens & Serial", r.lensSerialDisplay, true));
        }
        if (!r.softwareDisplay.isEmpty()) {
            r.findings.add(new Finding("系统版本", "Software", r.softwareDisplay, false));
        }
        if (r.metadataSegmentsCount > 0) {
            r.findings.add(new Finding(
                    "元数据区块",
                    "Metadata Blocks",
                    I18n.tr(
                            r.metadataSegmentsCount + " 个区块 (" + r.metadataTotalBytes + " B)",
                            r.metadataSegmentsCount + " blocks (" + r.metadataTotalBytes + " B)"),
                    false));
        }
        if (r.fileNameLeaks) {
            r.findings.add(new Finding(
                    "原文件名",
                    "Original Filename",
                    r.originalFileName,
                    false));
        }
        return r;
    }

    private static void inspectJpeg(byte[] data, Report r) {
        int pos = 2;
        int n = data.length;
        while (pos + 4 <= n) {
            if ((data[pos] & 0xFF) != 0xFF) {
                pos++;
                continue;
            }
            while (pos < n && (data[pos] & 0xFF) == 0xFF) {
                pos++;
            }
            if (pos >= n) break;
            int marker = data[pos] & 0xFF;
            pos++;

            if (marker == 0xD9 || marker == 0xDA) {
                break; // EOI or SOS
            }
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) {
                continue;
            }
            if (pos + 2 > n) break;
            int segLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            if (segLen < 2 || pos + segLen > n) break;

            int payloadStart = pos + 2;
            int payloadLen = segLen - 2;

            if (marker == 0xE1) { // APP1: EXIF or XMP
                r.metadataSegmentsCount++;
                r.metadataTotalBytes += segLen;
                if (payloadLen >= 6
                        && data[payloadStart] == 'E'
                        && data[payloadStart + 1] == 'x'
                        && data[payloadStart + 2] == 'i'
                        && data[payloadStart + 3] == 'f'
                        && data[payloadStart + 4] == 0
                        && data[payloadStart + 5] == 0) {
                    parseTiffExif(data, payloadStart + 6, payloadLen - 6, r);
                } else if (payloadLen >= 28) {
                    String head = new String(data, payloadStart, Math.min(payloadLen, 64), StandardCharsets.US_ASCII);
                    if (head.contains("http://ns.adobe.com/xap") && r.softwareDisplay.isEmpty()) {
                        r.softwareDisplay = "Adobe XMP Metadata";
                    }
                }
            } else if ((marker >= 0xE2 && marker <= 0xEF) || marker == 0xFE) {
                r.metadataSegmentsCount++;
                r.metadataTotalBytes += segLen;
                if (marker == 0xFE && payloadLen > 0 && r.softwareDisplay.isEmpty()) {
                    String comment = new String(data, payloadStart, Math.min(payloadLen, 80), StandardCharsets.UTF_8).trim();
                    if (!comment.isEmpty()) {
                        r.softwareDisplay = comment;
                    }
                }
            }

            pos += segLen;
        }
    }

    private static void inspectPng(byte[] data, Report r) {
        int pos = 8;
        int n = data.length;
        while (pos + 12 <= n) {
            int len = readIntBE(data, pos);
            if (len < 0 || pos + 12 + len > n) break;
            String type = new String(data, pos + 4, 4, StandardCharsets.US_ASCII);
            int payloadStart = pos + 8;
            if ("eXIf".equals(type)) {
                r.metadataSegmentsCount++;
                r.metadataTotalBytes += len + 12;
                parseTiffExif(data, payloadStart, len, r);
            } else if ("tEXt".equals(type) || "zTXt".equals(type) || "iTXt".equals(type) || "tIME".equals(type)) {
                r.metadataSegmentsCount++;
                r.metadataTotalBytes += len + 12;
                if ("tEXt".equals(type) && len > 0 && r.softwareDisplay.isEmpty()) {
                    String txt = new String(data, payloadStart, Math.min(len, 80), StandardCharsets.UTF_8)
                            .replace('\0', ':').trim();
                    if (!txt.isEmpty()) {
                        r.softwareDisplay = txt;
                    }
                }
            } else if ("IEND".equals(type)) {
                break;
            }
            pos += 12 + len;
        }
    }

    private static void inspectWebp(byte[] data, Report r) {
        int pos = 12;
        int n = data.length;
        while (pos + 8 <= n) {
            String fourCC = new String(data, pos, 4, StandardCharsets.US_ASCII);
            int chunkSize = readIntLE(data, pos + 4);
            if (chunkSize < 0 || pos + 8 + chunkSize > n) break;
            if ("EXIF".equals(fourCC) || "XMP ".equals(fourCC)) {
                r.metadataSegmentsCount++;
                r.metadataTotalBytes += chunkSize + 8;
                if ("EXIF".equals(fourCC)) {
                    int start = pos + 8;
                    int len = chunkSize;
                    if (len >= 6 && data[start] == 'E' && data[start + 1] == 'x' && data[start + 2] == 'i' && data[start + 3] == 'f') {
                        start += 6;
                        len -= 6;
                    }
                    parseTiffExif(data, start, len, r);
                }
            }
            pos += 8 + chunkSize + (chunkSize & 1);
        }
    }

    private static void parseTiffExif(byte[] data, int tiffStart, int tiffLen, Report r) {
        if (tiffLen < 8 || tiffStart + tiffLen > data.length) return;
        boolean littleEndian;
        if (data[tiffStart] == 'I' && data[tiffStart + 1] == 'I') {
            littleEndian = true;
        } else if (data[tiffStart] == 'M' && data[tiffStart + 1] == 'M') {
            littleEndian = false;
        } else {
            return;
        }

        int magic = readU16(data, tiffStart + 2, littleEndian);
        if (magic != 42) return;
        int ifd0Offset = readInt(data, tiffStart + 4, littleEndian);
        if (ifd0Offset < 8 || ifd0Offset >= tiffLen) return;

        String make = "";
        String model = "";
        String software = "";
        String dateTime = "";
        int exifIfdOffset = -1;
        int gpsIfdOffset = -1;

        int dirStart = tiffStart + ifd0Offset;
        if (dirStart + 2 > tiffStart + tiffLen) return;
        int entryCount = readU16(data, dirStart, littleEndian);
        int entriesBase = dirStart + 2;

        for (int i = 0; i < entryCount; i++) {
            int entryPos = entriesBase + i * 12;
            if (entryPos + 12 > tiffStart + tiffLen) break;
            int tag = readU16(data, entryPos, littleEndian);
            int type = readU16(data, entryPos + 2, littleEndian);
            int count = readInt(data, entryPos + 4, littleEndian);

            if (tag == 0x010F) {
                make = readAsciiTag(data, tiffStart, tiffLen, entryPos, type, count, littleEndian);
            } else if (tag == 0x0110) {
                model = readAsciiTag(data, tiffStart, tiffLen, entryPos, type, count, littleEndian);
            } else if (tag == 0x0131) {
                software = readAsciiTag(data, tiffStart, tiffLen, entryPos, type, count, littleEndian);
            } else if (tag == 0x0132) {
                dateTime = readAsciiTag(data, tiffStart, tiffLen, entryPos, type, count, littleEndian);
            } else if (tag == 0x8769) {
                exifIfdOffset = readInt(data, entryPos + 8, littleEndian);
            } else if (tag == 0x8825) {
                gpsIfdOffset = readInt(data, entryPos + 8, littleEndian);
            }
        }

        if (!make.isEmpty() || !model.isEmpty()) {
            if (!make.isEmpty() && !model.isEmpty() && !model.toLowerCase(Locale.ROOT).startsWith(make.toLowerCase(Locale.ROOT))) {
                r.deviceDisplay = make + " " + model;
            } else {
                r.deviceDisplay = !model.isEmpty() ? model : make;
            }
        }
        if (!software.isEmpty()) {
            r.softwareDisplay = software;
        }
        if (!dateTime.isEmpty()) {
            r.dateTimeDisplay = dateTime;
        }

        if (exifIfdOffset >= 8 && exifIfdOffset < tiffLen) {
            parseExifSubIfd(data, tiffStart, tiffLen, exifIfdOffset, littleEndian, r);
        }
        if (gpsIfdOffset >= 8 && gpsIfdOffset < tiffLen) {
            parseGpsIfd(data, tiffStart, tiffLen, gpsIfdOffset, littleEndian, r);
        }
    }

    private static void parseExifSubIfd(byte[] data, int tiffStart, int tiffLen, int subOffset, boolean le, Report r) {
        int dirStart = tiffStart + subOffset;
        if (dirStart + 2 > tiffStart + tiffLen) return;
        int entryCount = readU16(data, dirStart, le);
        int entriesBase = dirStart + 2;
        String lens = "";
        String serial = "";

        for (int i = 0; i < entryCount; i++) {
            int entryPos = entriesBase + i * 12;
            if (entryPos + 12 > tiffStart + tiffLen) break;
            int tag = readU16(data, entryPos, le);
            int type = readU16(data, entryPos + 2, le);
            int count = readInt(data, entryPos + 4, le);

            if (tag == 0x9003 || tag == 0x9004) {
                String dt = readAsciiTag(data, tiffStart, tiffLen, entryPos, type, count, le);
                if (!dt.isEmpty()) {
                    r.dateTimeDisplay = dt;
                }
            } else if (tag == 0xA434) {
                lens = readAsciiTag(data, tiffStart, tiffLen, entryPos, type, count, le);
            } else if (tag == 0xA431) {
                serial = readAsciiTag(data, tiffStart, tiffLen, entryPos, type, count, le);
            }
        }

        if (!lens.isEmpty() && !serial.isEmpty()) {
            r.lensSerialDisplay = lens + " · SN:" + serial;
        } else if (!lens.isEmpty()) {
            r.lensSerialDisplay = lens;
        } else if (!serial.isEmpty()) {
            r.lensSerialDisplay = "SN:" + serial;
        }
    }

    private static void parseGpsIfd(byte[] data, int tiffStart, int tiffLen, int gpsOffset, boolean le, Report r) {
        int dirStart = tiffStart + gpsOffset;
        if (dirStart + 2 > tiffStart + tiffLen) return;
        int entryCount = readU16(data, dirStart, le);
        int entriesBase = dirStart + 2;

        String latRef = "N";
        String lonRef = "E";
        double[] latDms = null;
        double[] lonDms = null;
        Double altitude = null;

        for (int i = 0; i < entryCount; i++) {
            int entryPos = entriesBase + i * 12;
            if (entryPos + 12 > tiffStart + tiffLen) break;
            int tag = readU16(data, entryPos, le);
            int type = readU16(data, entryPos + 2, le);
            int count = readInt(data, entryPos + 4, le);

            if (tag == 0x0001) {
                String ref = readAsciiTag(data, tiffStart, tiffLen, entryPos, type, count, le);
                if (!ref.isEmpty()) latRef = ref.substring(0, 1).toUpperCase(Locale.ROOT);
            } else if (tag == 0x0002 && type == 5 && count == 3) {
                latDms = readThreeRationals(data, tiffStart, tiffLen, entryPos, le);
            } else if (tag == 0x0003) {
                String ref = readAsciiTag(data, tiffStart, tiffLen, entryPos, type, count, le);
                if (!ref.isEmpty()) lonRef = ref.substring(0, 1).toUpperCase(Locale.ROOT);
            } else if (tag == 0x0004 && type == 5 && count == 3) {
                lonDms = readThreeRationals(data, tiffStart, tiffLen, entryPos, le);
            } else if (tag == 0x0006 && type == 5 && count == 1) {
                int valOffset = readInt(data, entryPos + 8, le);
                if (valOffset >= 8 && valOffset + 8 <= tiffLen) {
                    long num = readInt(data, tiffStart + valOffset, le) & 0xFFFFFFFFL;
                    long den = readInt(data, tiffStart + valOffset + 4, le) & 0xFFFFFFFFL;
                    if (den != 0) altitude = (double) num / (double) den;
                }
            }
        }

        if (latDms != null && lonDms != null) {
            double lat = latDms[0] + latDms[1] / 60.0 + latDms[2] / 3600.0;
            double lon = lonDms[0] + lonDms[1] / 60.0 + lonDms[2] / 3600.0;
            if (lat > 0.0001 || lon > 0.0001) {
                r.hasGps = true;
                String coord = String.format(Locale.US, "%.5f° %s, %.5f° %s", lat, latRef, lon, lonRef);
                if (altitude != null && altitude > 0.0) {
                    coord += String.format(Locale.US, " (Alt %.1fm)", altitude);
                }
                r.gpsDisplay = coord;
            }
        }
    }

    private static double[] readThreeRationals(byte[] data, int tiffStart, int tiffLen, int entryPos, boolean le) {
        int valOffset = readInt(data, entryPos + 8, le);
        if (valOffset < 8 || valOffset + 24 > tiffLen) return null;
        double[] res = new double[3];
        for (int i = 0; i < 3; i++) {
            int p = tiffStart + valOffset + i * 8;
            long num = readInt(data, p, le) & 0xFFFFFFFFL;
            long den = readInt(data, p + 4, le) & 0xFFFFFFFFL;
            res[i] = den == 0 ? 0.0 : (double) num / (double) den;
        }
        return res;
    }

    private static String readAsciiTag(byte[] data, int tiffStart, int tiffLen, int entryPos, int type, int count, boolean le) {
        if (type != 2 || count <= 0 || count > 256) return "";
        int pos;
        if (count <= 4) {
            pos = entryPos + 8;
        } else {
            int offset = readInt(data, entryPos + 8, le);
            if (offset < 8 || offset + count > tiffLen) return "";
            pos = tiffStart + offset;
        }
        int end = pos;
        int limit = pos + count;
        while (end < limit && end < data.length && data[end] != 0) {
            end++;
        }
        return new String(data, pos, end - pos, StandardCharsets.UTF_8).trim();
    }

    /**
     * Strips all APP1..APP15 (EXIF, GPS, XMP, ICC, IPTC) and COM segments from a JPEG stream,
     * leaving only pure image frame headers and compressed pixel scan data.
     */
    public static byte[] stripJpegMetadata(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return jpeg;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length);
        out.write(0xFF);
        out.write(0xD8);

        int pos = 2;
        int n = jpeg.length;
        while (pos < n) {
            if ((jpeg[pos] & 0xFF) != 0xFF) {
                out.write(jpeg, pos, n - pos);
                break;
            }
            while (pos < n && (jpeg[pos] & 0xFF) == 0xFF) {
                pos++;
            }
            if (pos >= n) break;
            int marker = jpeg[pos] & 0xFF;
            pos++;

            if (marker == 0xD9) {
                out.write(0xFF);
                out.write(0xD9);
                break;
            }
            if (marker == 0xDA) { // SOS: write SOS + remaining scan bytes up to end
                out.write(0xFF);
                out.write(0xDA);
                out.write(jpeg, pos, n - pos);
                break;
            }
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) {
                out.write(0xFF);
                out.write(marker);
                continue;
            }
            if (pos + 2 > n) break;
            int segLen = ((jpeg[pos] & 0xFF) << 8) | (jpeg[pos + 1] & 0xFF);
            if (segLen < 2 || pos + segLen > n) break;

            // Strip APP1..APP15 (0xE1..0xEF) and COM (0xFE)
            boolean strip = (marker >= 0xE1 && marker <= 0xEF) || marker == 0xFE;
            if (!strip) {
                out.write(0xFF);
                out.write(marker);
                out.write(jpeg, pos, segLen);
            }
            pos += segLen;
        }
        return out.toByteArray();
    }

    /**
     * Injects a real binary APP1 EXIF + GPS segment into a JPEG byte array for instant in-app demo & verification.
     */
    public static byte[] injectDemoExif(byte[] cleanJpeg) {
        if (cleanJpeg == null || cleanJpeg.length < 4 || (cleanJpeg[0] & 0xFF) != 0xFF || (cleanJpeg[1] & 0xFF) != 0xD8) {
            return cleanJpeg;
        }
        byte[] app1Payload = buildBinaryExifPayload();
        int segLen = app1Payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(cleanJpeg.length + segLen + 4);
        out.write(0xFF);
        out.write(0xD8);
        out.write(0xFF);
        out.write(0xE1);
        out.write((segLen >> 8) & 0xFF);
        out.write(segLen & 0xFF);
        out.write(app1Payload, 0, app1Payload.length);
        out.write(cleanJpeg, 2, cleanJpeg.length - 2);
        return out.toByteArray();
    }

    private static byte[] buildBinaryExifPayload() {
        // Big-endian ("MM") TIFF structure with IFD0, ExifSubIFD, and GPSIFD
        ByteArrayOutputStream tiff = new ByteArrayOutputStream(512);
        // Header (8 bytes): "MM", 0x002A, offset to IFD0 = 8
        writeBytes(tiff, new byte[]{'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08});

        String make = "Apple\0";
        String model = "iPhone 16 Pro\0";
        String software = "iOS 18.1.1\0";
        String dateTime = "2026:10:31 14:28:09\0";
        String lens = "24mm f/1.78 Main OIS\0";
        String serial = "F2L984201US\0";

        // IFD0 at offset 8: 6 entries -> 2 + 6*12 + 4 = 78 bytes (ends at offset 86)
        // Data pool for IFD0 starts at offset 86:
        int offMake = 86;
        int offModel = offMake + make.length();
        int offSoft = offModel + model.length();
        int offDate = offSoft + software.length();
        int exifIfdOff = (offDate + dateTime.length() + 1) & ~1; // align even

        // ExifSubIFD at exifIfdOff: 3 entries -> 2 + 3*12 + 4 = 42 bytes
        int exifDataStart = exifIfdOff + 42;
        int offDtOrig = exifDataStart;
        int offLens = offDtOrig + dateTime.length();
        int offSerial = offLens + lens.length();
        int gpsIfdOff = (offSerial + serial.length() + 1) & ~1;

        // GPSIFD at gpsIfdOff: 5 entries -> 2 + 5*12 + 4 = 66 bytes
        int gpsDataStart = gpsIfdOff + 66;
        int offLat = gpsDataStart;       // 24 bytes (3 rationals)
        int offLon = offLat + 24;        // 24 bytes (3 rationals)
        int offAlt = offLon + 24;        // 8 bytes (1 rational)

        // Write IFD0 (6 entries)
        writeU16BE(tiff, 6);
        writeIfdEntryBE(tiff, 0x010F, 2, make.length(), offMake);
        writeIfdEntryBE(tiff, 0x0110, 2, model.length(), offModel);
        writeIfdEntryBE(tiff, 0x0131, 2, software.length(), offSoft);
        writeIfdEntryBE(tiff, 0x0132, 2, dateTime.length(), offDate);
        writeIfdEntryBE(tiff, 0x8769, 4, 1, exifIfdOff);
        writeIfdEntryBE(tiff, 0x8825, 4, 1, gpsIfdOff);
        writeIntBE(tiff, 0); // next IFD = 0

        // Write IFD0 data strings
        writeAscii(tiff, make);
        writeAscii(tiff, model);
        writeAscii(tiff, software);
        writeAscii(tiff, dateTime);
        if (tiff.size() < exifIfdOff) tiff.write(0);

        // Write ExifSubIFD (3 entries)
        writeU16BE(tiff, 3);
        writeIfdEntryBE(tiff, 0x9003, 2, dateTime.length(), offDtOrig);
        writeIfdEntryBE(tiff, 0xA434, 2, lens.length(), offLens);
        writeIfdEntryBE(tiff, 0xA431, 2, serial.length(), offSerial);
        writeIntBE(tiff, 0);

        writeAscii(tiff, dateTime);
        writeAscii(tiff, lens);
        writeAscii(tiff, serial);
        if (tiff.size() < gpsIfdOff) tiff.write(0);

        // Write GPSIFD (5 entries)
        writeU16BE(tiff, 5);
        writeIfdEntryBE(tiff, 0x0001, 2, 2, ('N' << 24)); // "N\0" inline in 4-byte value
        writeIfdEntryBE(tiff, 0x0002, 5, 3, offLat);
        writeIfdEntryBE(tiff, 0x0003, 2, 2, ('W' << 24)); // "W\0" inline in 4-byte value
        writeIfdEntryBE(tiff, 0x0004, 5, 3, offLon);
        writeIfdEntryBE(tiff, 0x0006, 5, 1, offAlt);
        writeIntBE(tiff, 0);

        // Lat: 37 deg, 46 min, 29.75 sec N (San Francisco, CA)
        writeRationalBE(tiff, 37, 1);
        writeRationalBE(tiff, 46, 1);
        writeRationalBE(tiff, 2975, 100);

        // Lon: 122 deg, 25 min, 9.90 sec W
        writeRationalBE(tiff, 122, 1);
        writeRationalBE(tiff, 25, 1);
        writeRationalBE(tiff, 990, 100);

        // Alt: 18.5m
        writeRationalBE(tiff, 185, 10);

        ByteArrayOutputStream full = new ByteArrayOutputStream(tiff.size() + 6);
        writeBytes(full, new byte[]{'E', 'x', 'i', 'f', 0, 0});
        byte[] tiffBytes = tiff.toByteArray();
        full.write(tiffBytes, 0, tiffBytes.length);
        return full.toByteArray();
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }

    private static void writeU16BE(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeIntBE(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeIfdEntryBE(ByteArrayOutputStream out, int tag, int type, int count, int valueOrOffset) {
        writeU16BE(out, tag);
        writeU16BE(out, type);
        writeIntBE(out, count);
        writeIntBE(out, valueOrOffset);
    }

    private static void writeRationalBE(ByteArrayOutputStream out, int num, int den) {
        writeIntBE(out, num);
        writeIntBE(out, den);
    }

    private static int readU16(byte[] b, int off, boolean le) {
        if (le) {
            return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
        }
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int readInt(byte[] b, int off, boolean le) {
        if (le) {
            return readIntLE(b, off);
        }
        return readIntBE(b, off);
    }

    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }
}
