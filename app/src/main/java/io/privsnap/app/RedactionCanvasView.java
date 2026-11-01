package io.privsnap.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewParent;
import java.util.ArrayList;
import java.util.List;

public final class RedactionCanvasView extends View {

    public interface OnCanvasStateChangeListener {
        void onStateChanged(int actionCount, int redoCount, float zoomScale);
    }

    private Bitmap originalBitmap;
    private Bitmap frostedBuffer;
    private Bitmap mosaicBuffer;

    private final List<PixelScrubber.RedactionAction> actions = new ArrayList<>();
    private final List<PixelScrubber.RedactionAction> redoStack = new ArrayList<>();

    private int currentMode = PixelScrubber.MODE_FROSTED;
    private int currentShape = PixelScrubber.SHAPE_BRUSH;
    private float brushSizeRatio = 0.048f; // relative to min(imgW, imgH)
    private boolean panZoomOnlyMode = false;

    private boolean watermarkEnabled = false;
    private String watermarkText = "";

    // Viewport transform
    private float userScale = 1.0f;
    private float userTransX = 0f;
    private float userTransY = 0f;
    private final Matrix drawMatrix = new Matrix();
    private final Matrix inverseMatrix = new Matrix();

    // Active drawing gesture in image space
    private boolean isDrawing = false;
    private boolean isMultiTouch = false;
    private Path activePath;
    private float startImgX, startImgY;
    private float curImgX, curImgY;
    private float lastTouchX, lastTouchY;

    private final Paint bgPaint = new Paint();
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private OnCanvasStateChangeListener listener;

    public RedactionCanvasView(Context context) {
        super(context);
        bgPaint.setColor(0xFF0F172A);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(3f);
        guidePaint.setColor(0xCC38BDF8);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float prevScale = userScale;
                userScale = Math.max(1.0f, Math.min(5.0f, userScale * factor));
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                float actualFactor = userScale / prevScale;
                userTransX = focusX - (focusX - userTransX) * actualFactor;
                userTransY = focusY - (focusY - userTransY) * actualFactor;
                clampPan();
                notifyListener();
                invalidate();
                return true;
            }
        });
    }

    public void setOnCanvasStateChangeListener(OnCanvasStateChangeListener l) {
        this.listener = l;
    }

    public void setImage(Bitmap bitmap) {
        this.originalBitmap = bitmap;
        this.actions.clear();
        this.redoStack.clear();
        this.userScale = 1.0f;
        this.userTransX = 0f;
        this.userTransY = 0f;
        if (bitmap != null) {
            int minDim = Math.min(bitmap.getWidth(), bitmap.getHeight());
            int blurRadius = Math.max(12, minDim / 32);
            int mosaicBlock = Math.max(14, minDim / 36);
            this.frostedBuffer = PixelScrubber.createFrostedGlassBitmap(bitmap, blurRadius);
            this.mosaicBuffer = PixelScrubber.createMosaicBitmap(bitmap, mosaicBlock);
        } else {
            this.frostedBuffer = null;
            this.mosaicBuffer = null;
        }
        notifyListener();
        requestLayout();
        invalidate();
    }

    public boolean hasImage() {
        return originalBitmap != null;
    }

    public void setRedactionMode(int mode) {
        this.currentMode = mode;
        this.panZoomOnlyMode = false;
    }

    public int getRedactionMode() {
        return currentMode;
    }

    public void setShapeMode(int shape) {
        this.currentShape = shape;
        this.panZoomOnlyMode = false;
    }

    public int getShapeMode() {
        return currentShape;
    }

    public void setBrushSizePreset(int preset) {
        // 0 = Small, 1 = Medium, 2 = Large
        if (preset == 0) {
            this.brushSizeRatio = 0.028f;
        } else if (preset == 2) {
            this.brushSizeRatio = 0.078f;
        } else {
            this.brushSizeRatio = 0.048f;
        }
    }

    public void setPanZoomOnlyMode(boolean enabled) {
        this.panZoomOnlyMode = enabled;
    }

    public boolean isPanZoomOnlyMode() {
        return panZoomOnlyMode;
    }

    public void resetZoom() {
        this.userScale = 1.0f;
        this.userTransX = 0f;
        this.userTransY = 0f;
        notifyListener();
        invalidate();
    }

    public void setWatermark(boolean enabled, String text) {
        this.watermarkEnabled = enabled;
        this.watermarkText = text != null ? text : "";
        invalidate();
    }

    public boolean isWatermarkEnabled() {
        return watermarkEnabled;
    }

    public String getWatermarkText() {
        return watermarkText;
    }

    public boolean canUndo() {
        return !actions.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public int getActionCount() {
        return actions.size();
    }

    public void undo() {
        if (!actions.isEmpty()) {
            PixelScrubber.RedactionAction last = actions.remove(actions.size() - 1);
            redoStack.add(last);
            notifyListener();
            invalidate();
        }
    }

    public void redo() {
        if (!redoStack.isEmpty()) {
            PixelScrubber.RedactionAction next = redoStack.remove(redoStack.size() - 1);
            actions.add(next);
            notifyListener();
            invalidate();
        }
    }

    public void clearAllRedactions() {
        actions.clear();
        redoStack.clear();
        watermarkEnabled = false;
        notifyListener();
        invalidate();
    }

    public Bitmap renderCleanFinalBitmap() {
        if (originalBitmap == null) return null;
        return PixelScrubber.renderFinalBitmap(
                originalBitmap,
                frostedBuffer,
                mosaicBuffer,
                actions,
                watermarkEnabled,
                watermarkText);
    }

    private float getStrokeWidthInImageSpace() {
        if (originalBitmap == null) return 32f;
        int minDim = Math.min(originalBitmap.getWidth(), originalBitmap.getHeight());
        return Math.max(12f, minDim * brushSizeRatio);
    }

    private void updateMatrices() {
        drawMatrix.reset();
        if (originalBitmap == null || getWidth() <= 0 || getHeight() <= 0) return;

        float vw = getWidth();
        float vh = getHeight();
        float iw = originalBitmap.getWidth();
        float ih = originalBitmap.getHeight();

        float pad = 20f;
        float baseScale = Math.min((vw - pad * 2f) / iw, (vh - pad * 2f) / ih);
        float baseDx = (vw - iw * baseScale) * 0.5f;
        float baseDy = (vh - ih * baseScale) * 0.5f;

        drawMatrix.postScale(baseScale, baseScale);
        drawMatrix.postTranslate(baseDx, baseDy);
        drawMatrix.postScale(userScale, userScale, vw * 0.5f, vh * 0.5f);
        drawMatrix.postTranslate(userTransX, userTransY);

        drawMatrix.invert(inverseMatrix);
    }

    private void clampPan() {
        if (userScale <= 1.01f) {
            userScale = 1.0f;
            userTransX = 0f;
            userTransY = 0f;
            return;
        }
        float maxPanX = getWidth() * 0.45f * userScale;
        float maxPanY = getHeight() * 0.45f * userScale;
        userTransX = Math.max(-maxPanX, Math.min(maxPanX, userTransX));
        userTransY = Math.max(-maxPanY, Math.min(maxPanY, userTransY));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) width = 1080;
        int desiredHeight = (int) (width * 1.06f);
        if (originalBitmap != null && originalBitmap.getWidth() > 0 && originalBitmap.getHeight() > 0) {
            float ratio = (float) originalBitmap.getHeight() / (float) originalBitmap.getWidth();
            int minH = (int) (width * 0.68f);
            int maxH = (int) (width * 1.22f);
            desiredHeight = Math.max(minH, Math.min(maxH, (int) (width * ratio)));
        }
        setMeasuredDimension(width, desiredHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        if (originalBitmap == null) return;

        updateMatrices();

        canvas.save();
        canvas.concat(drawMatrix);

        canvas.drawBitmap(originalBitmap, 0f, 0f, null);

        List<PixelScrubber.RedactionAction> drawList = actions;
        if (isDrawing && !isMultiTouch) {
            drawList = new ArrayList<>(actions);
            if (currentShape == PixelScrubber.SHAPE_BRUSH && activePath != null) {
                drawList.add(new PixelScrubber.RedactionAction(
                        currentMode, PixelScrubber.SHAPE_BRUSH, getStrokeWidthInImageSpace(), activePath, null));
            } else if (currentShape == PixelScrubber.SHAPE_RECT) {
                RectF r = makeRect(startImgX, startImgY, curImgX, curImgY);
                drawList.add(new PixelScrubber.RedactionAction(
                        currentMode, PixelScrubber.SHAPE_RECT, getStrokeWidthInImageSpace(), null, r));
            }
        }

        PixelScrubber.drawRedactionActions(canvas, frostedBuffer, mosaicBuffer, drawList);

        if (isDrawing && !isMultiTouch && currentShape == PixelScrubber.SHAPE_RECT) {
            RectF r = makeRect(startImgX, startImgY, curImgX, curImgY);
            canvas.drawRect(r, guidePaint);
        }

        if (watermarkEnabled && watermarkText != null && !watermarkText.trim().isEmpty()) {
            PixelScrubber.drawTiledWatermark(
                    canvas, originalBitmap.getWidth(), originalBitmap.getHeight(), watermarkText.trim());
        }

        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (originalBitmap == null) return false;

        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }

        scaleDetector.onTouchEvent(event);
        int action = event.getActionMasked();

        if (event.getPointerCount() > 1) {
            isMultiTouch = true;
            isDrawing = false;
            activePath = null;
            invalidate();
        }

        updateMatrices();
        float[] pts = new float[]{event.getX(), event.getY()};
        inverseMatrix.mapPoints(pts);
        float imgX = clamp(pts[0], 0f, originalBitmap.getWidth());
        float imgY = clamp(pts[1], 0f, originalBitmap.getHeight());

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                isMultiTouch = false;
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                if (!panZoomOnlyMode) {
                    isDrawing = true;
                    startImgX = imgX;
                    startImgY = imgY;
                    curImgX = imgX;
                    curImgY = imgY;
                    if (currentShape == PixelScrubber.SHAPE_BRUSH) {
                        activePath = new Path();
                        activePath.moveTo(imgX, imgY);
                        activePath.lineTo(imgX + 0.5f, imgY + 0.5f);
                    }
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (panZoomOnlyMode || isMultiTouch) {
                    if (!scaleDetector.isInProgress() && event.getPointerCount() == 1) {
                        userTransX += (event.getX() - lastTouchX);
                        userTransY += (event.getY() - lastTouchY);
                        clampPan();
                        invalidate();
                    }
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    return true;
                }
                if (isDrawing) {
                    curImgX = imgX;
                    curImgY = imgY;
                    if (currentShape == PixelScrubber.SHAPE_BRUSH && activePath != null) {
                        activePath.lineTo(imgX, imgY);
                    }
                    invalidate();
                }
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;

            case MotionEvent.ACTION_UP:
                if (isDrawing && !isMultiTouch && !panZoomOnlyMode) {
                    isDrawing = false;
                    if (currentShape == PixelScrubber.SHAPE_BRUSH && activePath != null) {
                        activePath.lineTo(imgX, imgY);
                        actions.add(new PixelScrubber.RedactionAction(
                                currentMode, PixelScrubber.SHAPE_BRUSH, getStrokeWidthInImageSpace(), activePath, null));
                        redoStack.clear();
                    } else if (currentShape == PixelScrubber.SHAPE_RECT) {
                        RectF r = makeRect(startImgX, startImgY, imgX, imgY);
                        if (r.width() > 4f && r.height() > 4f) {
                            actions.add(new PixelScrubber.RedactionAction(
                                    currentMode, PixelScrubber.SHAPE_RECT, getStrokeWidthInImageSpace(), null, r));
                            redoStack.clear();
                        }
                    }
                    activePath = null;
                    notifyListener();
                    invalidate();
                }
                isMultiTouch = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                isDrawing = false;
                isMultiTouch = false;
                activePath = null;
                invalidate();
                return true;
        }
        return true;
    }

    private void notifyListener() {
        if (listener != null) {
            listener.onStateChanged(actions.size(), redoStack.size(), userScale);
        }
    }

    private static RectF makeRect(float x1, float y1, float x2, float y2) {
        return new RectF(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2));
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
