package com.example.einkpoc;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String TAG = "EinkPoC";
    private ENoteBridge bridge;
    private TextView statusText;
    private DrawView drawView;
    private boolean fastInkActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Global crash handler
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            String[] paths = {"/sdcard/Download/einkpoc_crash.txt", getFilesDir() + "/crash.txt"};
            for (String path : paths) {
                try {
                    java.io.FileWriter fw = new java.io.FileWriter(path, true);
                    java.io.PrintWriter pw = new java.io.PrintWriter(fw);
                    pw.println("=== UNCAUGHT " + new java.util.Date() + " thread:" + t.getName() + " ===");
                    e.printStackTrace(pw);
                    pw.close();
                    break;
                } catch (Throwable ignored) {}
            }
            if (defaultHandler != null) defaultHandler.uncaughtException(t, e);
            else System.exit(1);
        });

        bridge = new ENoteBridge();
        boolean ok = bridge.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // Row 1: main controls
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(8, 8, 8, 4);

        addButton(row1, "Info", v -> refreshInfo());
        addButton(row1, "Fast Ink ON", v -> enableFastInk());
        addButton(row1, "Fast Ink OFF", v -> disableFastInk());
        addButton(row1, "Clear", v -> drawView.clear());

        // Row 2: display modes
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(8, 0, 8, 4);

        addButton(row2, "FAST(4)", v -> setMode(4));
        addButton(row2, "GL16(3)", v -> setMode(3));
        addButton(row2, "GC(17)", v -> setMode(17));

        // Status text
        statusText = new TextView(this);
        statusText.setPadding(16, 8, 16, 8);
        statusText.setTextSize(10);
        statusText.setTextColor(Color.BLACK);

        // Drawing canvas
        drawView = new DrawView(this, bridge);
        drawView.setBackgroundColor(Color.WHITE);

        root.addView(row1, wrapLp());
        root.addView(row2, wrapLp());
        root.addView(statusText, wrapLp());
        root.addView(drawView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        setContentView(root);

        statusText.setText(ok ? "Ready. Tap 'Fast Ink ON' to enable WritingSurface."
                : "FAILED to connect to ENoteSetting!");
    }

    private LinearLayout.LayoutParams wrapLp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void addButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(10);
        btn.setAllCaps(false);
        btn.setPadding(8, 4, 8, 4);
        btn.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.setMargins(2, 0, 2, 0);
        parent.addView(btn, lp);
    }

    private void refreshInfo() {
        String info = bridge.getInfo();
        statusText.setText(info);
        dumpToFile("einkpoc_info.txt", info);
    }

    private void enableFastInk() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Enabling Fast Ink (WritingSurface path) ===\n");

        // Step 1: Set application context (required before any writing calls)
        try {
            java.lang.reflect.Method setCtx = bridge.getEnote().getClass()
                    .getMethod("setApplicationContext", android.content.Context.class);
            setCtx.invoke(bridge.getEnote(), getApplicationContext());
            sb.append("setApplicationContext: OK\n");
        } catch (Throwable e) {
            sb.append("setApplicationContext: FAIL: ").append(e.getMessage()).append("\n");
        }

        // Step 2: Initialize writing system (loads libpaintworker.so native state)
        sb.append("initWriting: ").append(bridge.initWriting()).append("\n");

        // Step 3: Set display to FAST mode
        sb.append("setPictureMode(FAST): ").append(bridge.setPictureMode(4)).append("\n");

        // Step 4: Set render delay to 0 for immediate rendering
        sb.append("setRenderWritingDelayCount(0): ").append(bridge.setRenderWritingDelayCount(0)).append("\n");

        // Step 5: Enable writing — this inits WritingSurface and connects to WritingBufferQueue
        sb.append("setWritingEnabled(true): ").append(bridge.setWritingEnabled(true)).append("\n");

        fastInkActive = true;
        drawView.setFastInkActive(true);

        sb.append("\nWritingSurface enabled! Draw with the stylus.\n");
        statusText.setText(sb.toString());
        dumpToFile("einkpoc_fastink.txt", sb.toString());
    }

    private void disableFastInk() {
        bridge.setWritingEnabled(false);
        bridge.setPictureMode(3); // GL16
        fastInkActive = false;
        drawView.setFastInkActive(false);
        statusText.setText("Fast ink disabled. Mode set to GL16.");
    }

    private void setMode(int mode) {
        String result = bridge.setPictureMode(mode);
        int current = bridge.getPictureMode();
        statusText.setText("setPictureMode(" + mode + "): " + result + "\nCurrent: " + current);
    }

    private void dumpToFile(String filename, String content) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter("/sdcard/Download/" + filename);
            fw.write(content);
            fw.close();
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (fastInkActive) {
            disableFastInk();
        }
    }

    /**
     * Drawing view using the WritingSurface fast ink path.
     *
     * How it works:
     * - We maintain an offscreen Bitmap that we draw strokes into
     * - On pen down: call onWritingStart(), provide bitmap via setWritingJavaBitmap()
     * - On each move: draw stroke segment into bitmap, call renderWriting(dirtyRect)
     *   to push the dirty region to the WritingSurface overlay via SurfaceFlinger
     * - On pen up: call onWritingEnd() to trigger quality redraw
     *
     * The WritingSurface is a separate compositor layer that SurfaceFlinger blends
     * on top of our app's normal window. renderWriting() tells libpaintworker.so
     * to blit the dirty rect from our bitmap to that overlay surface.
     */
    static class DrawView extends View {
        private static final double LOG4 = Math.log(4.0);

        private final ENoteBridge bridge;
        private final java.util.List<StrokeData> completedStrokes = new java.util.ArrayList<>();
        private StrokeData currentStroke = null;
        private boolean fastInkActive = false;
        private final Paint strokePaint;

        // Pen width params
        private float penWidthMin = 1.0f;
        private float penWidthMax = 5.0f;

        // Offscreen bitmap for WritingSurface rendering
        private Bitmap writingBitmap;
        private Canvas writingCanvas;
        private boolean bitmapProvided = false;

        public DrawView(android.content.Context context, ENoteBridge bridge) {
            super(context);
            this.bridge = bridge;
            strokePaint = new Paint();
            strokePaint.setColor(Color.BLACK);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setAntiAlias(true);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);
        }

        public void setFastInkActive(boolean active) {
            this.fastInkActive = active;
            if (active) {
                ensureBitmap();
            }
        }

        public void setPenWidthParams(float min, float max) {
            this.penWidthMin = min;
            this.penWidthMax = max;
        }

        private float pressureToWidth(float pressure) {
            float range = penWidthMax - penWidthMin;
            return penWidthMin + (float)(range * Math.log(3.0 * pressure + 1.0) / LOG4);
        }

        private void ensureBitmap() {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                // View not laid out yet — defer
                post(() -> ensureBitmap());
                return;
            }
            if (writingBitmap == null || writingBitmap.getWidth() != w || writingBitmap.getHeight() != h) {
                writingBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                writingCanvas = new Canvas(writingBitmap);
                writingCanvas.drawColor(Color.TRANSPARENT);
                bitmapProvided = false;
            }
        }

        private void provideBitmapIfNeeded() {
            if (!bitmapProvided && writingBitmap != null) {
                // Get view's position on screen to offset the bitmap
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                bridge.setWritingJavaBitmap(writingBitmap, 0, loc[0], loc[1]);
                bitmapProvided = true;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();

            if (fastInkActive) {
                return handleTouchFastInk(event, action);
            } else {
                return handleTouchNormal(event, action);
            }
        }

        /**
         * WritingSurface fast ink path:
         * Draw into offscreen bitmap, call renderWriting() to push dirty rects
         */
        private boolean handleTouchFastInk(MotionEvent event, int action) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    ensureBitmap();
                    provideBitmapIfNeeded();
                    bridge.onWritingStart();

                    currentStroke = new StrokeData();
                    currentStroke.setWidthParams(penWidthMin, penWidthMax);
                    addPointToStroke(event);
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (currentStroke != null) {
                        float minX = event.getX(), minY = event.getY();
                        float maxX = minX, maxY = minY;

                        // Process historical points
                        for (int i = 0; i < event.getHistorySize(); i++) {
                            float hx = event.getHistoricalX(i);
                            float hy = event.getHistoricalY(i);
                            float hp = event.getHistoricalPressure(i);

                            float prevX = currentStroke.lastX();
                            float prevY = currentStroke.lastY();
                            float w = pressureToWidth(hp);

                            // Draw segment into offscreen bitmap
                            strokePaint.setStrokeWidth(w);
                            writingCanvas.drawLine(prevX, prevY, hx, hy, strokePaint);

                            currentStroke.addPoint(hx, hy, hp);

                            // Track dirty rect bounds
                            minX = Math.min(minX, Math.min(prevX, hx));
                            minY = Math.min(minY, Math.min(prevY, hy));
                            maxX = Math.max(maxX, Math.max(prevX, hx));
                            maxY = Math.max(maxY, Math.max(prevY, hy));
                        }

                        // Current point
                        float cx = event.getX(), cy = event.getY();
                        float cp = event.getPressure();
                        float prevX = currentStroke.lastX();
                        float prevY = currentStroke.lastY();
                        float w = pressureToWidth(cp);

                        strokePaint.setStrokeWidth(w);
                        writingCanvas.drawLine(prevX, prevY, cx, cy, strokePaint);
                        currentStroke.addPoint(cx, cy, cp);

                        minX = Math.min(minX, Math.min(prevX, cx));
                        minY = Math.min(minY, Math.min(prevY, cy));
                        maxX = Math.max(maxX, Math.max(prevX, cx));
                        maxY = Math.max(maxY, Math.max(prevY, cy));

                        // Push dirty rect to WritingSurface overlay
                        // Add padding for stroke width
                        float maxW = penWidthMax + 2;
                        int[] loc = new int[2];
                        getLocationOnScreen(loc);
                        Rect dirty = new Rect(
                                (int)(minX - maxW) + loc[0],
                                (int)(minY - maxW) + loc[1],
                                (int)(maxX + maxW) + loc[0],
                                (int)(maxY + maxW) + loc[1]);
                        bridge.renderWriting(dirty);
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    if (currentStroke != null) {
                        addPointToStroke(event);
                        completedStrokes.add(currentStroke);
                        currentStroke = null;
                        bridge.onWritingEnd();
                        // After overlay clears, do a normal View invalidate for persistence
                        postDelayed(() -> invalidate(), 900);
                    }
                    break;
            }
            return true;
        }

        /**
         * Normal (non-fast-ink) path: just record points and invalidate once on pen-up.
         */
        private boolean handleTouchNormal(MotionEvent event, int action) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    currentStroke = new StrokeData();
                    currentStroke.setWidthParams(penWidthMin, penWidthMax);
                    addPointToStroke(event);
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (currentStroke != null) {
                        for (int i = 0; i < event.getHistorySize(); i++) {
                            currentStroke.addPoint(
                                    event.getHistoricalX(i),
                                    event.getHistoricalY(i),
                                    event.getHistoricalPressure(i));
                        }
                        addPointToStroke(event);
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    if (currentStroke != null) {
                        addPointToStroke(event);
                        completedStrokes.add(currentStroke);
                        currentStroke = null;
                        invalidate();
                    }
                    break;
            }
            return true;
        }

        private void addPointToStroke(MotionEvent event) {
            if (currentStroke != null) {
                currentStroke.addPoint(event.getX(), event.getY(), event.getPressure());
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Draw completed strokes
            for (StrokeData stroke : completedStrokes) {
                drawStroke(canvas, stroke);
            }
            // Draw current stroke (only in non-fast-ink mode)
            if (!fastInkActive && currentStroke != null) {
                drawStroke(canvas, currentStroke);
            }
        }

        private void drawStroke(Canvas canvas, StrokeData stroke) {
            if (stroke.points.size() < 2) return;
            for (int i = 1; i < stroke.points.size(); i++) {
                float[] prev = stroke.points.get(i - 1);
                float[] curr = stroke.points.get(i);
                strokePaint.setStrokeWidth(stroke.widths.get(i));
                canvas.drawLine(prev[0], prev[1], curr[0], curr[1], strokePaint);
            }
        }

        public void clear() {
            completedStrokes.clear();
            currentStroke = null;
            if (writingBitmap != null) {
                writingBitmap.eraseColor(Color.TRANSPARENT);
            }
            bitmapProvided = false;
            invalidate();
        }
    }

    /** Stroke data with precomputed widths */
    static class StrokeData {
        final java.util.List<float[]> points = new java.util.ArrayList<>();
        final java.util.List<Float> widths = new java.util.ArrayList<>();

        private static final double LOG4 = Math.log(4.0);
        private float wMin = 1.0f;
        private float wMax = 3.5f;

        void setWidthParams(float min, float max) {
            this.wMin = min;
            this.wMax = max;
        }

        void addPoint(float x, float y, float pressure) {
            points.add(new float[]{x, y, pressure});
            float range = wMax - wMin;
            float w = wMin + (float)(range * Math.log(3.0 * pressure + 1.0) / LOG4);
            widths.add(w);
        }

        float lastX() {
            if (points.isEmpty()) return 0;
            return points.get(points.size() - 1)[0];
        }

        float lastY() {
            if (points.isEmpty()) return 0;
            return points.get(points.size() - 1)[1];
        }
    }
}
