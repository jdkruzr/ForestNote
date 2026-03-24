package com.example.einkpoc;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
    private boolean autoDrawActive = false;

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

        // Pre-set MobileSheets write type so FocusMonitor activates WriteHelp on focus
        try {
            android.provider.Settings.Global.putInt(getContentResolver(), "mobilesheets_write_type", 1);
            android.provider.Settings.Global.putString(getContentResolver(), "mobilesheets_last_stop_package", "com.zubersoft.mobilesheetspro");
        } catch (Throwable e) {
            // Log but don't crash
            try {
                java.io.FileWriter fw = new java.io.FileWriter("/sdcard/Download/einkpoc_settings.txt");
                fw.write("Settings.Global.putInt failed: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n");
                e.printStackTrace(new java.io.PrintWriter(fw));
                fw.close();
            } catch (Throwable ignored) {}
        }

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
        drawView = new DrawView(this);
        drawView.setBackgroundColor(Color.WHITE);

        root.addView(row1, wrapLp());
        root.addView(row2, wrapLp());
        root.addView(statusText, wrapLp());
        root.addView(drawView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        setContentView(root);

        statusText.setText(ok ? "Ready. Tap 'Fast Ink ON' to enable accelerated drawing."
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

    private void probeNative() {
        statusText.setText("Running native probe... (may crash if lib fails to init)");
        // Run in a thread so if it hangs we don't ANR
        new Thread(() -> {
            NativeProbe probe = new NativeProbe();
            String result = probe.run();
            runOnUiThread(() -> statusText.setText(result));
        }).start();
    }

    private void refreshInfo() {
        String info = bridge.getInfo();
        statusText.setText(info);
        dumpToFile("einkpoc_info.txt", info);
    }

    private void enableFastInk() {
        // Pre-set MobileSheets write type so FocusMonitor activates WriteHelp
        try {
            android.provider.Settings.Global.putInt(getContentResolver(), "mobilesheets_write_type", 1);
        } catch (Throwable e) {
            // May fail without WRITE_SETTINGS permission, that's OK
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;

        StringBuilder sb = new StringBuilder();
        sb.append("=== Enabling Fast Ink ===\n");

        // Call initWriting to load libpaintworker.so in our process
        // (requires SELinux rules added via magiskpolicy)
        // Note: WritingSurface::init will fail but auto-draw rects might still work
        sb.append("initWriting: ").append(bridge.initWriting()).append("\n");

        // Now try to set auto-draw rects directly via ENoteWriting native methods
        // These are in libpaintworker.so which is now loaded in our process
        sb.append("setNativeAutoDrawRects: ").append(bridge.setNativeAutoDrawRects(w, h)).append("\n");

        // Set display to FAST mode
        sb.append("setPictureMode(FAST): ").append(bridge.setPictureMode(4)).append("\n");

        // Enable AutoDraw via service calls
        sb.append("setT1000AutoDrawEnable(true): ").append(bridge.setAutoDrawEnabled(true)).append("\n");
        sb.append("setAllRegionUnAutoDraw(false): ").append(bridge.setAllRegionUnAutoDraw(false)).append("\n");
        sb.append("setAutoDrawToolType(2/pen): ").append(bridge.setAutoDrawToolType(2)).append("\n");
        sb.append("setAutoDrawPenWidthRange(").append(penMin).append(",").append(penMax).append("): ")
                .append(bridge.setAutoDrawPenWidthRange(penMin, penMax)).append("\n");
        // Get the DrawView's actual screen position
        int[] loc = new int[2];
        drawView.getLocationOnScreen(loc);
        int dvLeft = loc[0];
        int dvTop = loc[1];
        int dvRight = dvLeft + drawView.getWidth();
        int dvBottom = dvTop + drawView.getHeight();

        sb.append("Screen: ").append(w).append("x").append(h).append("\n");
        sb.append("DrawView on screen: (").append(dvLeft).append(",").append(dvTop)
                .append(")-(").append(dvRight).append(",").append(dvBottom).append(")\n");

        // Try multiple coordinate spaces to find what works
        // Android coords (portrait)
        sb.append("addAutoDrawRect(android 0,0,").append(w).append(",").append(h).append("): ")
                .append(bridge.addAutoDrawRect(new Rect(0, 0, w, h))).append("\n");
        // Physical coords (landscape, pre-rotation: 1920x1440)
        sb.append("addAutoDrawRect(physical 0,0,1920,1440): ")
                .append(bridge.addAutoDrawRect(new Rect(0, 0, 1920, 1440))).append("\n");
        // Oversized rect to cover everything regardless of coord space
        sb.append("addAutoDrawRect(oversized 0,0,2000,2000): ")
                .append(bridge.addAutoDrawRect(new Rect(0, 0, 2000, 2000))).append("\n");

        // T1000 direct commands — try setting handwriting range on the chip itself
        // T1000_CMD_SET_HANDWRITING_ENABLE = 17 (enable=1)
        sb.append("T1000 SET_HANDWRITING_ENABLE(1): ")
                .append(bridge.callT1000Cmd(17, new int[]{1})).append("\n");
        // T1000_CMD_SEND_HANDWRITING_RANGE = 14 (left, top, right, bottom)
        // Try Android coords
        sb.append("T1000 SEND_HANDWRITING_RANGE(0,0,").append(w).append(",").append(h).append("): ")
                .append(bridge.callT1000Cmd(14, new int[]{0, 0, w, h})).append("\n");
        // Try physical coords
        sb.append("T1000 SEND_HANDWRITING_RANGE(0,0,1920,1440): ")
                .append(bridge.callT1000Cmd(14, new int[]{0, 0, 1920, 1440})).append("\n");

        autoDrawActive = true;
        drawView.setAutoDrawActive(true);

        sb.append("\nFast ink enabled! Draw with the stylus.\n");
        statusText.setText(sb.toString());
        dumpToFile("einkpoc_fastink.txt", sb.toString());
    }

    private void disableFastInk() {
        bridge.setAutoDrawEnabled(false);
        bridge.setAllRegionUnAutoDraw(true);
        bridge.stopHandwriteInterceptMipi();
        bridge.setPictureMode(3); // GL16
        autoDrawActive = false;
        drawView.setAutoDrawActive(false);
        statusText.setText("Fast ink disabled. Mode set to GL16.");
    }

    private void setMode(int mode) {
        String result = bridge.setPictureMode(mode);
        int current = bridge.getPictureMode();
        statusText.setText("setPictureMode(" + mode + "): " + result + "\nCurrent: " + current);
    }

    private int penMin = 1;
    private int penMax = 3;

    private void showPenWidthDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("AutoDraw Pen Width");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        TextView minLabel = new TextView(this);
        minLabel.setText("Min width (current: " + penMin + "):");
        minLabel.setTextColor(Color.BLACK);
        layout.addView(minLabel);

        android.widget.EditText minInput = new android.widget.EditText(this);
        minInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        minInput.setText(String.valueOf(penMin));
        minInput.setTextColor(Color.BLACK);
        layout.addView(minInput);

        TextView maxLabel = new TextView(this);
        maxLabel.setText("Max width (current: " + penMax + "):");
        maxLabel.setTextColor(Color.BLACK);
        layout.addView(maxLabel);

        android.widget.EditText maxInput = new android.widget.EditText(this);
        maxInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        maxInput.setText(String.valueOf(penMax));
        maxInput.setTextColor(Color.BLACK);
        layout.addView(maxInput);

        builder.setView(layout);
        builder.setPositiveButton("Apply", (dialog, which) -> {
            try {
                penMin = Integer.parseInt(minInput.getText().toString());
                penMax = Integer.parseInt(maxInput.getText().toString());
                String result = bridge.setAutoDrawPenWidthRange(penMin, penMax);
                statusText.setText("Pen width set to " + penMin + "-" + penMax + ": " + result);
            } catch (Throwable e) {
                statusText.setText("Invalid input: " + e.getMessage());
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
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
        if (autoDrawActive) {
            disableFastInk();
        }
    }

    /**
     * Drawing view that coordinates with the T1000 AutoDraw overlay.
     *
     * When AutoDraw is active:
     * - During pen movement: don't invalidate (let the overlay render)
     * - After pen-up: wait for overlay to clear, then render final strokes
     *
     * Strokes use pressure sensitivity to match the overlay's rendering.
     */
    static class DrawView extends View {
        private static final long REDRAW_DELAY_MS = 900; // slightly after overlay's 800ms clear

        private final java.util.List<StrokeData> completedStrokes = new java.util.ArrayList<>();
        private StrokeData currentStroke = null;
        private boolean autoDrawActive = false;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint strokePaint;

        public DrawView(android.content.Context context) {
            super(context);
            strokePaint = new Paint();
            strokePaint.setColor(Color.BLACK);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setAntiAlias(true);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);
        }

        public void setAutoDrawActive(boolean active) {
            this.autoDrawActive = active;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    currentStroke = new StrokeData();
                    addPoint(event);
                    invalidate();
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (currentStroke != null) {
                        for (int i = 0; i < event.getHistorySize(); i++) {
                            currentStroke.addPoint(
                                    event.getHistoricalX(i),
                                    event.getHistoricalY(i),
                                    event.getHistoricalPressure(i));
                        }
                        addPoint(event);
                        invalidate();
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    if (currentStroke != null) {
                        addPoint(event);
                        completedStrokes.add(currentStroke);
                        currentStroke = null;
                        invalidate();
                    }
                    break;
            }
            return true;
        }

        private void addPoint(MotionEvent event) {
            if (currentStroke != null) {
                currentStroke.addPoint(event.getX(), event.getY(), event.getPressure());
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Draw completed strokes with pressure-sensitive width
            for (StrokeData stroke : completedStrokes) {
                drawStroke(canvas, stroke);
            }
            // Draw current stroke (only when not in AutoDraw mode)
            if (!autoDrawActive && currentStroke != null) {
                drawStroke(canvas, currentStroke);
            }
        }

        private void drawStroke(Canvas canvas, StrokeData stroke) {
            if (stroke.points.size() < 2) return;
            for (int i = 1; i < stroke.points.size(); i++) {
                float[] prev = stroke.points.get(i - 1);
                float[] curr = stroke.points.get(i);
                // Pressure-sensitive width: map pressure to 1-6px
                float pressure = curr[2];
                float width = 1f + (pressure * 5f);
                strokePaint.setStrokeWidth(width);
                canvas.drawLine(prev[0], prev[1], curr[0], curr[1], strokePaint);
            }
        }

        public void clear() {
            completedStrokes.clear();
            currentStroke = null;
            invalidate();
        }
    }

    /** Simple stroke data: list of (x, y, pressure) points */
    static class StrokeData {
        final java.util.List<float[]> points = new java.util.ArrayList<>();

        void addPoint(float x, float y, float pressure) {
            points.add(new float[]{x, y, pressure});
        }
    }
}
