package io.privsnap.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {

    private static final int REQ_PICK_IMAGE = 1001;

    private byte[] currentRawBytes;
    private String currentOriginalName = "";
    private Bitmap currentBitmap;
    private ExifInspector.Report currentReport;
    private boolean metadataScrubbed = false;
    private int strippedBytesCount = 0;
    private String assignedCleanName = "";

    private RedactionCanvasView canvasView;
    private LinearLayout rootContainer;
    private int selectedBrushPreset = 1; // 0=S, 1=M, 2=L

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        I18n.init(this);
        setupStatusBar();

        canvasView = new RedactionCanvasView(this);
        canvasView.setOnCanvasStateChangeListener((actionCount, redoCount, zoomScale) -> refreshToolbarState());

        rebuildUi();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void setupStatusBar() {
        Window w = getWindow();
        if (w != null && Build.VERSION.SDK_INT >= 23) {
            w.setStatusBarColor(0xFFF8FAFC);
            w.setNavigationBarColor(0xFFFFFFFF);
            int flags = w.getDecorView().getSystemUiVisibility();
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();
        if (Intent.ACTION_SEND.equals(action) && type != null && type.startsWith("image/")) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                loadFromUri(uri);
            }
        }
    }

    private void openImagePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(
                    intent,
                    I18n.tr("选择照片或截图", "Select Image")),
                    REQ_PICK_IMAGE);
        } catch (Throwable t) {
            Toast.makeText(this, I18n.tr("无法打开相册选择器", "Unable to open photo picker"), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            loadFromUri(data.getData());
        }
    }

    private void loadFromUri(Uri uri) {
        try {
            ContentResolver cr = getContentResolver();
            String displayName = queryDisplayName(cr, uri);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(65536);
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) {
                    Toast.makeText(this, I18n.tr("无法读取所选图片", "Could not read image"), Toast.LENGTH_SHORT).show();
                    return;
                }
                byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, n);
                }
            }
            byte[] raw = buffer.toByteArray();
            loadRawImageBytes(raw, displayName);
        } catch (Throwable t) {
            Toast.makeText(this, I18n.tr("加载图片失败", "Failed to load image"), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadDemoPhoto() {
        byte[] demoBytes = PixelScrubber.createDemoPhotoWithRealExif();
        loadRawImageBytes(demoBytes, "IMG_20261031_142809_Camera.jpg");
    }

    private void loadRawImageBytes(byte[] raw, String fileName) {
        if (raw == null || raw.length == 0) return;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);

        int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
        int sampleSize = 1;
        while (maxSide / sampleSize > 2048) {
            sampleSize *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
        if (decoded == null) {
            Toast.makeText(this, I18n.tr("不支持的图片格式", "Unsupported image format"), Toast.LENGTH_SHORT).show();
            return;
        }

        this.currentRawBytes = raw;
        this.currentOriginalName = (fileName != null && !fileName.isEmpty()) ? fileName : "IMG_20261031.jpg";
        this.currentBitmap = decoded;
        this.currentReport = ExifInspector.inspect(raw, this.currentOriginalName);
        this.metadataScrubbed = false;
        this.strippedBytesCount = 0;
        this.assignedCleanName = PixelScrubber.generateCleanFileName("jpg");

        canvasView.setImage(decoded);
        rebuildUi();
    }

    private void performInstantMetadataScrub() {
        if (currentBitmap == null) return;
        int beforeMetaBytes = currentReport != null ? currentReport.metadataTotalBytes : 0;
        byte[] cleanBytes = PixelScrubber.exportCleanJpeg(currentBitmap, 95);
        this.assignedCleanName = PixelScrubber.generateCleanFileName("jpg");
        this.currentRawBytes = cleanBytes;
        this.currentReport = ExifInspector.inspect(cleanBytes, assignedCleanName);
        this.metadataScrubbed = true;
        this.strippedBytesCount = Math.max(beforeMetaBytes, 0);
        rebuildUi();
        Toast.makeText(
                this,
                I18n.tr("已移除 EXIF/GPS 元数据并更新文件名", "EXIF/GPS stripped & filename randomized"),
                Toast.LENGTH_SHORT).show();
    }

    private void showWatermarkDialog() {
        if (currentBitmap == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(I18n.tr("图片水印", "Watermark Overlay"));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, dp(12), pad, dp(8));

        TextView hint = new TextView(this);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        hint.setTextColor(0xFF475569);
        hint.setText(I18n.tr(
                "添加斜向重复水印（常用于证件或合同防盗用）：",
                "Tiled diagonal watermark text for ID scans or documents:"));
        layout.addView(hint);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String defaultText = I18n.tr(
                "仅供办理业务核验使用 严禁他用 " + today,
                "FOR VERIFICATION ONLY - DO NOT REUSE " + today);
        String current = canvasView.getWatermarkText();
        input.setText((current != null && !current.isEmpty()) ? current : defaultText);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(12);
        layout.addView(input, inputLp);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.VERTICAL);
        presets.setPadding(0, dp(10), 0, 0);
        String[] presetItems = new String[]{
                I18n.tr("仅供办理业务核验使用 严禁他用 " + today, "FOR VERIFICATION ONLY - DO NOT REUSE " + today),
                I18n.tr("仅供实名核验使用 他用无效", "FOR ID CHECK ONLY - VOID IF COPIED"),
                I18n.tr("内部资料 禁止外传", "CONFIDENTIAL - DO NOT DISTRIBUTE")
        };
        for (final String pText : presetItems) {
            TextView chip = new TextView(this);
            chip.setText("• " + pText);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            chip.setTextColor(0xFF0284C7);
            chip.setPadding(dp(8), dp(6), dp(8), dp(6));
            chip.setOnClickListener(v -> input.setText(pText));
            presets.addView(chip);
        }
        layout.addView(presets);

        b.setView(layout);
        b.setPositiveButton(I18n.tr("确定", "Apply"), (d, w) -> {
            String txt = input.getText().toString().trim();
            canvasView.setWatermark(!txt.isEmpty(), txt);
            rebuildUi();
        });
        if (canvasView.isWatermarkEnabled()) {
            b.setNeutralButton(I18n.tr("清除水印", "Remove"), (d, w) -> {
                canvasView.setWatermark(false, "");
                rebuildUi();
            });
        }
        b.setNegativeButton(I18n.tr("取消", "Cancel"), null);
        b.show();
    }

    private void showZeroPermissionAuditDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(I18n.tr("权限与处理机制", "Permissions & Processing"));
        String msg = I18n.tr(
                "1. 无网络权限：\n"
                        + "AndroidManifest.xml 中未声明 android.permission.INTERNET，无法发起任何网络连接。\n\n"
                        + "2. 无相册读取权限：\n"
                        + "仅通过系统文件选择器 (ACTION_GET_CONTENT) 或系统分享 (ACTION_SEND) 接收单张图片。\n\n"
                        + "3. 元数据剥离与哈希更新：\n"
                        + "导出时移除所有 APP1..APP15 (EXIF, GPS, XMP, ICC, IPTC) 与注释块，修改边角 4 个像素的最低有效位 (LSB) 以更新文件 SHA-256，并生成随机文件名。",
                "1. No Internet Permission:\n"
                        + "android.permission.INTERNET is not declared in AndroidManifest.xml.\n\n"
                        + "2. No Storage Permissions:\n"
                        + "Only reads the single URI passed via the system file picker or Android share sheet.\n\n"
                        + "3. Binary Strip & Hash Salt:\n"
                        + "Removes APP1..APP15 (EXIF, GPS, XMP, ICC, IPTC) and COM markers, flips 1 LSB on 4 corner pixels so the exported file gets a new SHA-256 hash, and writes a random PS_<hex>.jpg filename.");
        b.setMessage(msg);
        b.setPositiveButton(I18n.tr("关闭", "Close"), null);
        b.show();
    }

    private void saveCleanImageToGallery() {
        if (currentBitmap == null) return;
        try {
            Bitmap finalBmp = canvasView.renderCleanFinalBitmap();
            byte[] cleanBytes = PixelScrubber.exportCleanJpeg(finalBmp, 95);
            ExifInspector.Report verify = ExifInspector.inspect(cleanBytes, assignedCleanName);
            if (!verify.isCompletelyClean()) {
                Toast.makeText(this, I18n.tr("元数据校验失败", "Metadata check failed"), Toast.LENGTH_SHORT).show();
                return;
            }

            String fileName = PixelScrubber.generateCleanFileName("jpg");
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PrivSnap");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }

            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("MediaStore insert returned null");
            }
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("OutputStream null");
                out.write(cleanBytes);
                out.flush();
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            }

            this.metadataScrubbed = true;
            this.assignedCleanName = fileName;
            this.currentRawBytes = cleanBytes;
            this.currentReport = verify;
            rebuildUi();

            Toast.makeText(
                    this,
                    I18n.tr("已保存至 Pictures/PrivSnap/" + fileName,
                            "Saved to Pictures/PrivSnap/" + fileName),
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, I18n.tr("保存失败: ", "Save failed: ") + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareCleanImage() {
        if (currentBitmap == null) return;
        try {
            Bitmap finalBmp = canvasView.renderCleanFinalBitmap();
            byte[] cleanBytes = PixelScrubber.exportCleanJpeg(finalBmp, 95);
            String fileName = PixelScrubber.generateCleanFileName("jpg");
            ExifInspector.Report verify = ExifInspector.inspect(cleanBytes, fileName);

            File shareDir = CleanFileProvider.getShareDir(this);
            File[] oldFiles = shareDir.listFiles();
            if (oldFiles != null) {
                for (File f : oldFiles) {
                    f.delete();
                }
            }
            File outFile = new File(shareDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(cleanBytes);
                fos.flush();
            }

            this.metadataScrubbed = true;
            this.assignedCleanName = fileName;
            this.currentRawBytes = cleanBytes;
            this.currentReport = verify;
            rebuildUi();

            Uri contentUri = CleanFileProvider.getUriForFile(this, outFile);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("image/jpeg");
            share.putExtra(Intent.EXTRA_STREAM, contentUri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(
                    share,
                    I18n.tr("分享图片 (" + fileName + ")", "Share (" + fileName + ")")));
        } catch (Throwable t) {
            Toast.makeText(this, I18n.tr("分享失败: ", "Share failed: ") + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void rebuildUi() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(0xFFF8FAFC);

        screen.addView(buildTopBar());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scroll.setLayoutParams(scrollLp);

        rootContainer = new LinearLayout(this);
        rootContainer.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        rootContainer.setPadding(pad, dp(8), pad, dp(24));

        if (currentBitmap == null) {
            rootContainer.addView(buildEmptyStateCard());
            rootContainer.addView(buildSpecsCard());
        } else {
            rootContainer.addView(buildMetadataInspectorCard());
            rootContainer.addView(buildRedactionStudioCard());
        }

        scroll.addView(rootContainer);
        screen.addView(scroll);

        if (currentBitmap != null) {
            screen.addView(buildBottomActionBar());
        }

        setContentView(screen);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(16), dp(10));
        bar.setBackgroundColor(0xFFF8FAFC);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleCol.setLayoutParams(titleLp);

        TextView title = new TextView(this);
        title.setText(I18n.tr("净图 PrivSnap", "PrivSnap"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        title.setTextColor(0xFF0F172A);
        titleCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText(I18n.tr("EXIF/GPS 剥离与图片打码", "EXIF/GPS Stripper & Image Redactor"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        sub.setTextColor(0xFF64748B);
        titleCol.addView(sub);
        bar.addView(titleCol);

        TextView auditBtn = new TextView(this);
        auditBtn.setText(I18n.tr("0 权限", "0-Perm"));
        auditBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        auditBtn.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        auditBtn.setTextColor(0xFF047857);
        auditBtn.setBackground(roundedBg(0xFFD1FAE5, dp(14), 0, 0));
        auditBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        auditBtn.setOnClickListener(v -> showZeroPermissionAuditDialog());
        bar.addView(auditBtn);

        TextView langBtn = new TextView(this);
        langBtn.setText(I18n.isEn() ? "中文" : "EN");
        langBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        langBtn.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        langBtn.setTextColor(0xFF1E293B);
        langBtn.setBackground(roundedBg(0xFFE2E8F0, dp(14), 0, 0));
        langBtn.setPadding(dp(11), dp(6), dp(11), dp(6));
        LinearLayout.LayoutParams langLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        langLp.leftMargin = dp(8);
        langBtn.setLayoutParams(langLp);
        langBtn.setOnClickListener(v -> {
            I18n.toggleLanguage(this);
            rebuildUi();
        });
        bar.addView(langBtn);

        return bar;
    }

    private View buildEmptyStateCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBg(Color.WHITE, dp(18), dp(1), 0xFFE2E8F0));
        int pad = dp(20);
        card.setPadding(pad, dp(22), pad, dp(22));

        TextView headline = new TextView(this);
        headline.setText(I18n.tr("清除照片定位与敏感文字", "Strip EXIF/GPS & Redact Images"));
        headline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        headline.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        headline.setTextColor(0xFF0F172A);
        headline.setPadding(0, 0, 0, dp(8));
        card.addView(headline);

        TextView desc = new TextView(this);
        desc.setText(I18n.tr(
                "解析并移除 JPEG/PNG/WebP 中的 EXIF、GPS 坐标、设备序列号与时间戳，支持在图片上直接涂抹模糊或打码。应用不申请网络或存储权限，所有处理均在本地内存完成。",
                "Inspect and remove binary EXIF, GPS coordinates, camera serials, and timestamps from JPEG/PNG/WebP images, or blur/redact text before sharing. Requests zero permissions and runs entirely in memory."));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        desc.setTextColor(0xFF475569);
        desc.setLineSpacing(dp(3), 1f);
        card.addView(desc);

        TextView pickBtn = new TextView(this);
        pickBtn.setText(I18n.tr("选择图片", "Select Image"));
        pickBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        pickBtn.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        pickBtn.setTextColor(Color.WHITE);
        pickBtn.setGravity(Gravity.CENTER);
        pickBtn.setBackground(roundedBg(0xFF0F172A, dp(12), 0, 0));
        pickBtn.setPadding(dp(20), dp(13), dp(20), dp(13));
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pickLp.topMargin = dp(18);
        pickBtn.setLayoutParams(pickLp);
        pickBtn.setOnClickListener(v -> openImagePicker());
        card.addView(pickBtn);

        TextView demoBtn = new TextView(this);
        demoBtn.setText(I18n.tr("载入含 EXIF/GPS 的测试样张", "Load Sample JPEG (with EXIF & GPS)"));
        demoBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        demoBtn.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        demoBtn.setTextColor(0xFF1E293B);
        demoBtn.setGravity(Gravity.CENTER);
        demoBtn.setBackground(roundedBg(0xFFF1F5F9, dp(12), dp(1), 0xFFCBD5E1));
        demoBtn.setPadding(dp(16), dp(11), dp(16), dp(11));
        LinearLayout.LayoutParams demoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        demoLp.topMargin = dp(10);
        demoBtn.setLayoutParams(demoLp);
        demoBtn.setOnClickListener(v -> loadDemoPhoto());
        card.addView(demoBtn);

        return card;
    }

    private View buildSpecsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBg(Color.WHITE, dp(18), dp(1), 0xFFE2E8F0));
        int pad = dp(18);
        card.setPadding(pad, dp(16), pad, dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);

        TextView header = new TextView(this);
        header.setText(I18n.tr("功能说明", "Technical Notes"));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        header.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        header.setTextColor(0xFF0F172A);
        card.addView(header);

        String[][] items = new String[][]{
                {
                        I18n.tr("元数据剥离 (EXIF / GPS / XMP)", "Metadata Stripping (EXIF / GPS / XMP)"),
                        I18n.tr("移除 JPEG APP1..APP15、COM 注释块及 PNG eXIf/tEXt 块，导出时自动检查剩余块数量。",
                                "Strips JPEG APP1..APP15, COM markers, and PNG eXIf/tEXt chunks, then verifies the output byte stream.")
                },
                {
                        I18n.tr("SHA-256 哈希与文件名随机化", "SHA-256 Salt & Random Filename"),
                        I18n.tr("对 4 个边角像素做 1-bit 微调以改变文件 SHA-256 指纹，并将输出重命名为 PS_<hex>.jpg。",
                                "Flips 1 LSB on 4 corner pixels so exported files get a new SHA-256 hash, and renames output to PS_<hex>.jpg.")
                },
                {
                        I18n.tr("涂抹模糊、马赛克与防盗水印", "Blur, Mosaic, Blackout & Watermark"),
                        I18n.tr("支持三阶盒式模糊、像素马赛克、纯黑遮挡条以及斜向平铺水印，支持双指缩放。",
                                "3-pass box blur with noise grain, pixel mosaic, solid blackout bars, tiled watermark, and 2-finger zoom.")
                },
                {
                        I18n.tr("系统分享接入 (ACTION_SEND)", "System Share Sheet Integration"),
                        I18n.tr("可在系统相册或其他应用中点「分享 -> PrivSnap」直接传入图片。",
                                "Share images directly from any gallery or browser app into PrivSnap via the Android share menu.")
                }
        };

        for (String[] item : items) {
            TextView t = new TextView(this);
            t.setText(item[0]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            t.setTextColor(0xFF1E293B);
            t.setPadding(0, dp(10), 0, dp(2));
            card.addView(t);

            TextView d = new TextView(this);
            d.setText(item[1]);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            d.setTextColor(0xFF64748B);
            card.addView(d);
        }

        return card;
    }

    private View buildMetadataInspectorCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        card.setPadding(pad, dp(14), pad, dp(14));

        boolean clean = metadataScrubbed || (currentReport != null && currentReport.isCompletelyClean());
        if (clean) {
            card.setBackground(roundedBg(0xFFECFDF5, dp(16), dp(1), 0xFF6EE7B7));

            TextView title = new TextView(this);
            title.setText(I18n.tr(
                    "元数据已清理 (0 GPS / 0 EXIF)",
                    "Metadata Clean (0 GPS / 0 EXIF)"));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            title.setTextColor(0xFF065F46);
            card.addView(title);

            String hash = PixelScrubber.shortSha256(currentRawBytes);
            TextView detail = new TextView(this);
            String detailText = strippedBytesCount > 0
                    ? I18n.tr("已移除 " + strippedBytesCount + " B · 文件: " + assignedCleanName + " · SHA-256: " + hash,
                    "Removed " + strippedBytesCount + " B · File: " + assignedCleanName + " · SHA-256: " + hash)
                    : I18n.tr("无隐藏元数据 · 文件: " + assignedCleanName + " · SHA-256: " + hash,
                    "No metadata present · File: " + assignedCleanName + " · SHA-256: " + hash);
            detail.setText(detailText);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            detail.setTextColor(0xFF047857);
            detail.setPadding(0, dp(4), 0, 0);
            card.addView(detail);
        } else {
            boolean highRisk = currentReport != null && currentReport.hasGps;
            int bgColor = highRisk ? 0xFFFEF2F2 : 0xFFFFFBEB;
            int borderColor = highRisk ? 0xFFFCA5A5 : 0xFFFDE68A;
            int titleColor = highRisk ? 0xFF991B1B : 0xFF92400E;
            card.setBackground(roundedBg(bgColor, dp(16), dp(1), borderColor));

            int count = currentReport != null ? currentReport.findings.size() : 0;
            TextView title = new TextView(this);
            title.setText(highRisk
                    ? I18n.tr("检测到 GPS 坐标与设备元数据 (" + count + " 项)",
                    "GPS & Device Metadata Found (" + count + ")")
                    : I18n.tr("检测到元数据或原始文件名信息 (" + count + " 项)",
                    "Metadata / Filename Info Found (" + count + ")"));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            title.setTextColor(titleColor);
            card.addView(title);

            if (currentReport != null) {
                for (ExifInspector.Finding f : currentReport.findings) {
                    TextView row = new TextView(this);
                    row.setText(f.getCategory() + ":  " + f.value);
                    row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                    row.setTextColor(f.highRisk ? 0xFFB91C1C : 0xFF78350F);
                    row.setPadding(0, dp(4), 0, 0);
                    card.addView(row);
                }
            }

            TextView scrubNowBtn = new TextView(this);
            scrubNowBtn.setText(I18n.tr("剥离 EXIF 与 GPS 元数据", "Strip EXIF & GPS Now"));
            scrubNowBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            scrubNowBtn.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            scrubNowBtn.setTextColor(Color.WHITE);
            scrubNowBtn.setGravity(Gravity.CENTER);
            scrubNowBtn.setBackground(roundedBg(highRisk ? 0xFFDC2626 : 0xFFD97706, dp(10), 0, 0));
            scrubNowBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            btnLp.topMargin = dp(10);
            scrubNowBtn.setLayoutParams(btnLp);
            scrubNowBtn.setOnClickListener(v -> performInstantMetadataScrub());
            card.addView(scrubNowBtn);
        }

        return card;
    }

    private View buildRedactionStudioCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBg(Color.WHITE, dp(18), dp(1), 0xFFE2E8F0));
        int pad = dp(12);
        card.setPadding(pad, dp(12), pad, dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setPadding(dp(4), 0, dp(4), dp(8));

        TextView studioTitle = new TextView(this);
        studioTitle.setText(I18n.tr("画布编辑 (支持双指缩放)", "Canvas (2-Finger Pinch Zoom)"));
        studioTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        studioTitle.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        studioTitle.setTextColor(0xFF0F172A);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        studioTitle.setLayoutParams(stLp);
        topRow.addView(studioTitle);

        TextView changeBtn = new TextView(this);
        changeBtn.setText(I18n.tr("换图", "Open Another"));
        changeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        changeBtn.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        changeBtn.setTextColor(0xFF2563EB);
        changeBtn.setBackground(roundedBg(0xFFEFF6FF, dp(10), 0, 0));
        changeBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        changeBtn.setOnClickListener(v -> openImagePicker());
        topRow.addView(changeBtn);

        card.addView(topRow);

        if (canvasView.getParent() instanceof ViewGroup) {
            ((ViewGroup) canvasView.getParent()).removeView(canvasView);
        }
        LinearLayout.LayoutParams canvasLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        canvasView.setLayoutParams(canvasLp);
        card.addView(canvasView);

        HorizontalScrollView modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, dp(10), 0, dp(4));

        boolean isPan = canvasView.isPanZoomOnlyMode();
        int mode = canvasView.getRedactionMode();

        modeRow.addView(buildChip(
                I18n.tr("模糊", "Blur"),
                !isPan && mode == PixelScrubber.MODE_FROSTED,
                v -> {
                    canvasView.setRedactionMode(PixelScrubber.MODE_FROSTED);
                    rebuildUi();
                }));

        modeRow.addView(buildChip(
                I18n.tr("马赛克", "Mosaic"),
                !isPan && mode == PixelScrubber.MODE_MOSAIC,
                v -> {
                    canvasView.setRedactionMode(PixelScrubber.MODE_MOSAIC);
                    rebuildUi();
                }));

        modeRow.addView(buildChip(
                I18n.tr("黑条", "Blackout"),
                !isPan && mode == PixelScrubber.MODE_BLACKOUT,
                v -> {
                    canvasView.setRedactionMode(PixelScrubber.MODE_BLACKOUT);
                    rebuildUi();
                }));

        modeRow.addView(buildChip(
                canvasView.isWatermarkEnabled()
                        ? I18n.tr("水印 (开启)", "Watermark (On)")
                        : I18n.tr("水印", "Watermark"),
                canvasView.isWatermarkEnabled(),
                v -> showWatermarkDialog()));

        modeScroll.addView(modeRow);
        card.addView(modeScroll);

        HorizontalScrollView toolScroll = new HorizontalScrollView(this);
        toolScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout toolRow = new LinearLayout(this);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        toolRow.setPadding(0, dp(6), 0, dp(4));

        int shape = canvasView.getShapeMode();
        toolRow.addView(buildChip(
                I18n.tr("画笔", "Brush"),
                !isPan && shape == PixelScrubber.SHAPE_BRUSH,
                v -> {
                    canvasView.setShapeMode(PixelScrubber.SHAPE_BRUSH);
                    rebuildUi();
                }));

        toolRow.addView(buildChip(
                I18n.tr("矩形框", "Box"),
                !isPan && shape == PixelScrubber.SHAPE_RECT,
                v -> {
                    canvasView.setShapeMode(PixelScrubber.SHAPE_RECT);
                    rebuildUi();
                }));

        toolRow.addView(buildChip(
                I18n.tr("移动/缩放", "Pan / Zoom"),
                isPan,
                v -> {
                    canvasView.setPanZoomOnlyMode(!canvasView.isPanZoomOnlyMode());
                    rebuildUi();
                }));

        String[] sizeLabels = new String[]{"S", "M", "L"};
        for (int i = 0; i < 3; i++) {
            final int preset = i;
            toolRow.addView(buildChip(
                    sizeLabels[i],
                    selectedBrushPreset == preset,
                    v -> {
                        selectedBrushPreset = preset;
                        canvasView.setBrushSizePreset(preset);
                        rebuildUi();
                    }));
        }

        toolScroll.addView(toolRow);
        card.addView(toolScroll);

        LinearLayout histRow = new LinearLayout(this);
        histRow.setOrientation(LinearLayout.HORIZONTAL);
        histRow.setPadding(0, dp(8), 0, 0);

        histRow.addView(buildActionSmallBtn(
                I18n.tr("撤销", "Undo"),
                canvasView.canUndo(),
                v -> canvasView.undo()));

        histRow.addView(buildActionSmallBtn(
                I18n.tr("重做", "Redo"),
                canvasView.canRedo(),
                v -> canvasView.redo()));

        histRow.addView(buildActionSmallBtn(
                I18n.tr("重置缩放", "1:1 Zoom"),
                true,
                v -> canvasView.resetZoom()));

        histRow.addView(buildActionSmallBtn(
                I18n.tr("清空", "Clear"),
                canvasView.canUndo() || canvasView.isWatermarkEnabled(),
                v -> canvasView.clearAllRedactions()));

        card.addView(histRow);

        return card;
    }

    private View buildBottomActionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setPadding(dp(16), dp(12), dp(16), dp(14));

        TextView saveBtn = new TextView(this);
        saveBtn.setText(I18n.tr("保存图片", "Save"));
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        saveBtn.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        saveBtn.setTextColor(0xFF0F172A);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setBackground(roundedBg(0xFFE2E8F0, dp(12), 0, 0));
        saveBtn.setPadding(dp(12), dp(13), dp(12), dp(13));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        saveLp.rightMargin = dp(8);
        saveBtn.setLayoutParams(saveLp);
        saveBtn.setOnClickListener(v -> saveCleanImageToGallery());
        bar.addView(saveBtn);

        TextView shareBtn = new TextView(this);
        shareBtn.setText(I18n.tr("分享图片", "Share"));
        shareBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        shareBtn.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        shareBtn.setTextColor(Color.WHITE);
        shareBtn.setGravity(Gravity.CENTER);
        shareBtn.setBackground(roundedBg(0xFF0F172A, dp(12), 0, 0));
        shareBtn.setPadding(dp(12), dp(13), dp(12), dp(13));
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        shareBtn.setLayoutParams(shareLp);
        shareBtn.setOnClickListener(v -> shareCleanImage());
        bar.addView(shareBtn);

        return bar;
    }

    private View buildChip(String text, boolean active, View.OnClickListener onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(Typeface.create(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL));
        tv.setTextColor(active ? Color.WHITE : 0xFF334155);
        tv.setBackground(roundedBg(active ? 0xFF0F172A : 0xFFF1F5F9, dp(10), 0, 0));
        tv.setPadding(dp(13), dp(8), dp(13), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(onClick);
        return tv;
    }

    private View buildActionSmallBtn(String text, boolean enabled, View.OnClickListener onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        tv.setTextColor(enabled ? 0xFF1E293B : 0xFF94A3B8);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(roundedBg(enabled ? 0xFFF1F5F9 : 0xFFF8FAFC, dp(10), dp(1), 0xFFE2E8F0));
        tv.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        tv.setLayoutParams(lp);
        if (enabled) {
            tv.setOnClickListener(onClick);
        }
        return tv;
    }

    private void refreshToolbarState() {
        if (currentBitmap != null && !isFinishing()) {
            rebuildUi();
        }
    }

    private static String queryDisplayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Throwable ignored) {}
        String last = uri.getLastPathSegment();
        return last != null ? last : "photo.jpg";
    }

    private GradientDrawable roundedBg(int fillColor, int radiusPx, int strokeWidthPx, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(radiusPx);
        if (strokeWidthPx > 0) {
            gd.setStroke(strokeWidthPx, strokeColor);
        }
        return gd;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
