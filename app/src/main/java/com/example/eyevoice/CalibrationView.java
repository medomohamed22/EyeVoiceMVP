package com.example.eyevoice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class CalibrationView extends View {

    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float targetX = 0.5f, targetY = 0.5f;
    private float cursorX = 0.5f, cursorY = 0.5f;
    private boolean showCursor = true;

    public CalibrationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        targetPaint.setColor(0xFFFFB300);
        cursorPaint.setColor(0xFF00E5FF);
    }

    public void setTarget(float x, float y) {
        targetX = x; targetY = y; invalidate();
    }

    public void setCursor(float x, float y) {
        cursorX = x; cursorY = y; invalidate();
    }

    public void setShowCursor(boolean show) {
        showCursor = show; invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float min = Math.min(getWidth(), getHeight());
        float tx = targetX * getWidth();
        float ty = targetY * getHeight();

        targetPaint.setStyle(Paint.Style.STROKE);
        targetPaint.setStrokeWidth(Math.max(5f, min * 0.008f));
        canvas.drawCircle(tx, ty, min * 0.045f, targetPaint);
        targetPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(tx, ty, min * 0.012f, targetPaint);

        if (showCursor) {
            canvas.drawCircle(cursorX * getWidth(), cursorY * getHeight(),
                    min * 0.018f, cursorPaint);
        }
    }
}
