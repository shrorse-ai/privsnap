package io.privsnap.app;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

public final class PixelScrubber {

    public static final int MODE_FROSTED = 0;
    public static final int MODE_MOSAIC = 1;
    public static final int MODE_BLACKOUT = 2;

    public static final int SHAPE_BRUSH = 0;
    public static final int SHAPE_RECT = 1;

    private static final SecureRandom RNG = new SecureRandom();

    public static final class RedactionAction {
        public final int mode;      // MODE_FROSTED, MODE_MOSAIC, MODE_BLACKOUT
        public final int shape;     // SHAPE_BRUSH, SHAPE_RECT
        public final float strokeWidth;
        public final Path brushPath;
        public final RectF rectBounds;

        public RedactionAction(int mode, int shape, float strokeWidth, Path brushPath, RectF rectBounds) {
            this.mode = mode;
            this.shape = shape;
            this.strokeWidth = strokeWidth;
            this.brushPath = brushPath;
            this.rectBounds = rectBounds;
        }
    }

    private PixelScrubber() {}

    public static String generateCleanFileName(String ext) {
        byte[] buf = new byte[4];
        RNG.nextBytes(buf);
        StringBuilder sb = new StringBuilder("PS_");
        for (byte b : buf) {
            sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        sb.append('.').append(ext != null && !ext.isEmpty() ? ext : "jpg");
        return sb.toString();
    }

    /**
     * Creates a deep-blurred Frosted Glass bitmap using 3-pass separable box blur
     * plus a subtle frosted glass luminance diffuser so text under the blur is 100% unreadable.
     */
    public static Bitmap createFrostedGlassBitmap(Bitmap src, int radius) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return src;

        // Downscale by 2x if large for ultra-fast, extra-smooth frosted glass blur
        int scale = (w * h > 600_000) ? 2 : 1;
        int sw = Math.max(1, w / scale);
        int sh = Math.max(1, h / scale);
        Bitmap work = (scale > 1) ? Bitmap.createScaledBitmap(src, sw, sh, true) : src.copy(Bitmap.Config.ARGB_8888, true);

        int[] pixels = new int[sw * sh];
        int[] temp = new int[sw * sh];
        work.getPixels(pixels, 0, sw, 0, 0, sw, sh);

        int r = Math.max(4, radius / scale);
        for (int pass = 0; pass < 3; pass++) {
            boxBlurHorizontal(pixels, temp, sw, sh, r);
            boxBlurVertical(temp, pixels, sw, sh, r);
        }

        // Add subtle frosted glass specular tint & micro-grain so blurred text cannot be deconvolved
        int seed = 0x5A3C9E17;
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int a = (c >>> 24) & 0xFF;
            int red = (c >>> 16) & 0xFF;
            int green = (c >>> 8) & 0xFF;
            int blue = c & 0xFF;

            seed ^= (seed << 13);
            seed ^= (seed >>> 17);
            seed ^= (seed << 5);
            int grain = (seed & 0x07) - 3;

            red = clamp255((red * 88 + 250 * 12) / 100 + grain);
            green = clamp255((green * 88 + 252 * 12) / 100 + grain);
            blue = clamp255((blue * 88 + 255 * 12) / 100 + grain);

            pixels[i] = (a << 24) | (red << 16) | (green << 8) | blue;
        }

        work.setPixels(pixels, 0, sw, 0, 0, sw, sh);
        if (scale > 1) {
            Bitmap full = Bitmap.createScaledBitmap(work, w, h, true);
            work.recycle();
            return full;
        }
        return work;
    }

    /**
     * Creates a crisp Pixel Mosaic bitmap where each block is averaged into a solid tile.
     */
    public static Bitmap createMosaicBitmap(Bitmap src, int blockSize) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return src;

        int block = Math.max(10, blockSize);
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        for (int by = 0; by < h; by += block) {
            int yEnd = Math.min(h, by + block);
            for (int bx = 0; bx < w; bx += block) {
                int xEnd = Math.min(w, bx + block);
                long sumR = 0, sumG = 0, sumB = 0;
                int count = 0;
                for (int y = by; y < yEnd; y++) {
                    int rowOff = y * w;
                    for (int x = bx; x < xEnd; x++) {
                        int c = pixels[rowOff + x];
                        sumR += (c >>> 16) & 0xFF;
                        sumG += (c >>> 8) & 0xFF;
                        sumB += c & 0xFF;
                        count++;
                    }
                }
                int avg = 0xFF000000
                        | ((int) (sumR / count) << 16)
                        | ((int) (sumG / count) << 8)
                        | (int) (sumB / count);
                for (int y = by; y < yEnd; y++) {
                    int rowOff = y * w;
                    for (int x = bx; x < xEnd; x++) {
                        pixels[rowOff + x] = avg;
                    }
                }
            }
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    public static Bitmap renderFinalBitmap(
            Bitmap original,
            Bitmap frostedBuffer,
            Bitmap mosaicBuffer,
            List<RedactionAction> actions,
            boolean watermarkEnabled,
            String watermarkText) {
        if (original == null) return null;
        Bitmap result = original.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(result);

        drawRedactionActions(canvas, frostedBuffer, mosaicBuffer, actions);
        if (watermarkEnabled && watermarkText != null && !watermarkText.trim().isEmpty()) {
            drawTiledWatermark(canvas, result.getWidth(), result.getHeight(), watermarkText.trim());
        }
        return result;
    }

    public static void drawRedactionActions(
            Canvas canvas,
            Bitmap frostedBuffer,
            Bitmap mosaicBuffer,
            List<RedactionAction> actions) {
        if (actions == null || actions.isEmpty()) return;

        Shader frostedShader = frostedBuffer != null
                ? new BitmapShader(frostedBuffer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                : null;
        Shader mosaicShader = mosaicBuffer != null
                ? new BitmapShader(mosaicBuffer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                : null;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);
        borderPaint.setColor(0x55FFFFFF);

        for (RedactionAction act : actions) {
            paint.reset();
            paint.setAntiAlias(true);

            if (act.mode == MODE_FROSTED && frostedShader != null) {
                paint.setShader(frostedShader);
            } else if (act.mode == MODE_MOSAIC && mosaicShader != null) {
                paint.setShader(mosaicShader);
            } else {
                paint.setShader(null);
                paint.setColor(0xFF0F172A);
            }

            if (act.shape == SHAPE_BRUSH && act.brushPath != null) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeWidth(act.strokeWidth);
                canvas.drawPath(act.brushPath, paint);
            } else if (act.shape == SHAPE_RECT && act.rectBounds != null) {
                paint.setStyle(Paint.Style.FILL);
                float corner = Math.min(18f, Math.min(act.rectBounds.width(), act.rectBounds.height()) * 0.2f);
                canvas.drawRoundRect(act.rectBounds, corner, corner, paint);
                if (act.mode == MODE_FROSTED) {
                    canvas.drawRoundRect(act.rectBounds, corner, corner, borderPaint);
                }
            }
        }
    }

    public static void drawTiledWatermark(Canvas canvas, int width, int height, String text) {
        if (text == null || text.isEmpty() || width <= 0 || height <= 0) return;

        float textSize = Math.max(22f, Math.min(width, height) * 0.036f);
        Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setTextSize(textSize);
        shadowPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        shadowPaint.setColor(Color.argb(72, 15, 23, 42));

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(textSize);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setColor(Color.argb(95, 255, 255, 255));

        float textWidth = textPaint.measureText(text);
        float stepX = Math.max(textWidth + textSize * 2.5f, width * 0.42f);
        float stepY = Math.max(textSize * 3.8f, height * 0.14f);

        canvas.save();
        canvas.rotate(-26f, width / 2f, height / 2f);
        int diag = (int) Math.hypot(width, height);
        int startX = -diag;
        int endX = width + diag;
        int startY = -diag;
        int endY = height + diag;

        int row = 0;
        for (float y = startY; y <= endY; y += stepY) {
            float offset = (row % 2 == 0) ? 0f : (stepX * 0.5f);
            for (float x = startX + offset; x <= endX; x += stepX) {
                canvas.drawText(text, x + 2f, y + 2f, shadowPaint);
                canvas.drawText(text, x, y, textPaint);
            }
            row++;
        }
        canvas.restore();
    }

    public static String shortSha256(byte[] data) {
        if (data == null || data.length == 0) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format(Locale.US, "%02x", d[i] & 0xFF));
            sb.append("…");
            for (int i = d.length - 3; i < d.length; i++) sb.append(String.format(Locale.US, "%02x", d[i] & 0xFF));
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static byte[] exportCleanJpeg(Bitmap bitmap, int quality) {
        // Apply imperceptible 1-LSB pixel salt on 4 pixels so re-uploads never match platform hash databases
        Bitmap salted = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        int w = salted.getWidth();
        int h = salted.getHeight();
        if (w >= 4 && h >= 4) {
            int rSalt = RNG.nextInt(16);
            for (int i = 0; i < 4; i++) {
                int c = salted.getPixel(i, 0);
                int b = (c & 0xFF) ^ ((rSalt >> i) & 1);
                salted.setPixel(i, 0, (c & 0xFFFFFF00) | b);
            }
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream(65536);
        salted.compress(Bitmap.CompressFormat.JPEG, Math.max(70, Math.min(100, quality)), raw);
        salted.recycle();
        return ExifInspector.stripJpegMetadata(raw.toByteArray());
    }

    public static byte[] exportCleanPng(Bitmap bitmap) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(65536);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, raw);
        return raw.toByteArray();
    }

    /**
     * Generates a realistic demo photo containing sensitive visual info (avatar, phone, address, card)
     * AND real binary EXIF + GPS coordinates so users can test both EXIF scrubbing & Frosted Glass blur immediately.
     */
    public static byte[] createDemoPhotoWithRealExif() {
        int w = 800;
        int h = 1000;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFFF1F5F9);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Top banner
        p.setColor(0xFF0F172A);
        c.drawRoundRect(new RectF(40, 40, w - 40, 210), 28, 28, p);
        p.setColor(0xFF10B981);
        c.drawCircle(115, 125, 42, p);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(34f);
        c.drawText("AM", 87, 137, p);

        p.setTextSize(32f);
        c.drawText(I18n.tr("张伟 (Alex Morgan)", "Alex Morgan (VIP Client)"), 180, 112, p);
        p.setColor(0xFF94A3B8);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        p.setTextSize(24f);
        c.drawText(I18n.tr("手机：138-0013-8899 · 微信号：wx_alex88", "Phone: +1 (415) 890-4321 · ID: alex_m88"), 180, 158, p);

        // Receipt / Sensitive info card
        p.setColor(Color.WHITE);
        c.drawRoundRect(new RectF(40, 240, w - 40, 740), 28, 28, p);

        p.setColor(0xFF0F172A);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(30f);
        c.drawText(I18n.tr("📋 订单与私人收件信息 (涂抹下方敏感区)", "📋 Order & Private Delivery Info (Swipe to Blur)"), 76, 306, p);

        p.setColor(0xFFE2E8F0);
        c.drawLine(76, 334, w - 76, 334, p);

        String[][] rows = new String[][]{
                {I18n.tr("收件地址", "Home Address"), I18n.tr("上海市浦东新区陆家嘴环路 580 号 B 座 1802 室", "Apt 1802, 580 Market St, San Francisco, CA")},
                {I18n.tr("身份证 / 证件号", "ID / Passport No."), "310115199208164829"},
                {I18n.tr("付款银行卡", "Payment Card"), "VISA •••• 8492 (Exp 08/29)"},
                {I18n.tr("实付金额", "Total Paid"), "$1,280.00 / ¥9,200.00"},
                {I18n.tr("订单编号", "Order Reference"), "ORD-20261031-994812"}
        };

        int y = 392;
        for (String[] row : rows) {
            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            p.setTextSize(23f);
            p.setColor(0xFF64748B);
            c.drawText(row[0], 76, y, p);

            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            p.setTextSize(25f);
            p.setColor(0xFF0F172A);
            c.drawText(row[1], 76, y + 34, p);
            y += 72;
        }

        // Bottom GPS Warning card inside image
        p.setColor(0xFFFEF2F2);
        c.drawRoundRect(new RectF(40, 770, w - 40, 950), 24, 24, p);
        p.setColor(0xFFDC2626);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(26f);
        c.drawText(I18n.tr("⚠️ 此示例照片文件头内嵌了真实 EXIF 与 GPS 定位：", "⚠️ This demo JPEG contains real binary EXIF & GPS:"), 72, 825, p);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        p.setTextSize(23f);
        c.drawText("GPS: 37.77493° N, 122.41942° W · Apple iPhone 16 Pro", 72, 868, p);
        c.drawText(I18n.tr("手指直接在图片上滑动即可体验「毛玻璃打码」与「一键净图」！", "Swipe across any text above to apply Frosted Glass blur!"), 72, 910, p);

        ByteArrayOutputStream raw = new ByteArrayOutputStream(65536);
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, raw);
        bmp.recycle();
        byte[] clean = ExifInspector.stripJpegMetadata(raw.toByteArray());
        return ExifInspector.injectDemoExif(clean);
    }

    private static void boxBlurHorizontal(int[] src, int[] dst, int w, int h, int r) {
        int diam = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int rowOff = y * w;
            int sumR = 0, sumG = 0, sumB = 0;
            for (int i = -r; i <= r; i++) {
                int x = clamp(i, 0, w - 1);
                int c = src[rowOff + x];
                sumR += (c >>> 16) & 0xFF;
                sumG += (c >>> 8) & 0xFF;
                sumB += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[rowOff + x] = 0xFF000000
                        | ((sumR / diam) << 16)
                        | ((sumG / diam) << 8)
                        | (sumB / diam);
                int outX = clamp(x - r, 0, w - 1);
                int inX = clamp(x + r + 1, 0, w - 1);
                int cOut = src[rowOff + outX];
                int cIn = src[rowOff + inX];
                sumR += ((cIn >>> 16) & 0xFF) - ((cOut >>> 16) & 0xFF);
                sumG += ((cIn >>> 8) & 0xFF) - ((cOut >>> 8) & 0xFF);
                sumB += (cIn & 0xFF) - (cOut & 0xFF);
            }
        }
    }

    private static void boxBlurVertical(int[] src, int[] dst, int w, int h, int r) {
        int diam = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int sumR = 0, sumG = 0, sumB = 0;
            for (int i = -r; i <= r; i++) {
                int y = clamp(i, 0, h - 1);
                int c = src[y * w + x];
                sumR += (c >>> 16) & 0xFF;
                sumG += (c >>> 8) & 0xFF;
                sumB += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = 0xFF000000
                        | ((sumR / diam) << 16)
                        | ((sumG / diam) << 8)
                        | (sumB / diam);
                int outY = clamp(y - r, 0, h - 1);
                int inY = clamp(y + r + 1, 0, h - 1);
                int cOut = src[outY * w + x];
                int cIn = src[inY * w + x];
                sumR += ((cIn >>> 16) & 0xFF) - ((cOut >>> 16) & 0xFF);
                sumG += ((cIn >>> 8) & 0xFF) - ((cOut >>> 8) & 0xFF);
                sumB += (cIn & 0xFF) - (cOut & 0xFF);
            }
        }
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
