package com.local.reverse1999.subtitles;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

final class OcrRegionPicker implements AutoCloseable {
    private final FrameLayout panel;
    private final WindowManager windows;

    OcrRegionPicker(Context context, OcrRegion initial, Consumer<OcrRegion> finish) {
        windows = context.getSystemService(WindowManager.class);
        panel = new FrameLayout(context);
        float density = context.getResources().getDisplayMetrics().density;
        Selection selection = new Selection(context, initial);
        panel.addView(selection, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding((int) (12 * density), 0, (int) (12 * density), 0);
        controls.setBackgroundColor(0xf2111820);
        TextView instruction = new TextView(context);
        instruction.setText(R.string.ocr_region_instruction);
        instruction.setTextColor(Color.WHITE);
        controls.addView(instruction);
        LinearLayout actions = new LinearLayout(context);
        Button cancel = new Button(context);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(view -> finish.accept(null));
        Button save = new Button(context);
        save.setText(R.string.ocr_region_save);
        save.setOnClickListener(view -> finish.accept(selection.region));
        actions.addView(cancel);
        actions.addView(save);
        controls.addView(actions);
        FrameLayout.LayoutParams bar = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bar.topMargin = (int) (32 * density);
        panel.addView(controls, bar);
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 30) {
            layout.setFitInsetsTypes(0);
            layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        windows.addView(panel, layout);
    }

    @Override public void close() {
        windows.removeViewImmediate(panel);
    }

    private static final class Selection extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private OcrRegion region;
        private float downX, downY;

        Selection(Context context, OcrRegion initial) {
            super(context);
            region = initial;
            paint.setColor(0xffffd16f);
            paint.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
            paint.setStyle(Paint.Style.STROKE);
        }

        @Override protected void onDraw(Canvas canvas) {
            Rect area = region.pixels(getWidth(), getHeight());
            canvas.drawRect(area, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = Math.max(0, Math.min(1, event.getX() / getWidth()));
            float y = Math.max(0, Math.min(1, event.getY() / getHeight()));
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = x;
                downY = y;
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    || event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (Math.abs(x - downX) >= .08f && Math.abs(y - downY) >= .04f) {
                    region = new OcrRegion(Math.min(downX, x), Math.min(downY, y),
                            Math.max(downX, x), Math.max(downY, y));
                    invalidate();
                }
            }
            return true;
        }

        @Override public boolean performClick() { return super.performClick(); }
    }
}
