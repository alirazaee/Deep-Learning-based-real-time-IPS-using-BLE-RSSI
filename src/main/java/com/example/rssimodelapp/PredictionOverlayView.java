package com.example.rssimodelapp;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

public class PredictionOverlayView extends View {
    private float predictedX = -1;
    private float predictedY = -1;
    private Paint paint;
    private Bitmap mapBitmap;
    private Bitmap arrowBitmap;
    private float lastX = -1;
    private float lastY = -1;

    public PredictionOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(50);
        mapBitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.your_indoor_map); // Update with your map image resource
        arrowBitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.arrow); // Update with your arrow image resource
    }

    public void setPredictedCoordinates(float x, float y) {
        this.predictedX = x;
        this.predictedY = y;
        Log.d("PredictionOverlayView", "Set coordinates: x=" + x + ", y=" + y);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mapBitmap != null) {
            canvas.drawBitmap(mapBitmap, null, getMeasuredRect(), null);
        }
        if (predictedX >= 0 && predictedY >= 0) {
            float canvasWidth = canvas.getWidth();
            float canvasHeight = canvas.getHeight();
            float mapX = predictedX * canvasWidth;
            float mapY = predictedY * canvasHeight;

            // Draw arrow
            if (lastX >= 0 && lastY >= 0) {
                float angle = (float) Math.toDegrees(Math.atan2(mapY - lastY, mapX - lastX));
                Matrix matrix = new Matrix();
                matrix.postRotate(angle, arrowBitmap.getWidth() / 2, arrowBitmap.getHeight() / 2);
                matrix.postTranslate(mapX - arrowBitmap.getWidth() / 2, mapY - arrowBitmap.getHeight() / 2);
                canvas.drawBitmap(arrowBitmap, matrix, null);
            }

            lastX = mapX;
            lastY = mapY;
            Log.d("PredictionOverlayView", "Drawing at: mapX=" + mapX + ", mapY=" + mapY);
            canvas.drawCircle(mapX, mapY, 10, paint);
            canvas.drawText("X", mapX, mapY, paint);
        } else {
            Log.d("PredictionOverlayView", "Drawing test circle");
            // Draw a test circle to confirm the view is being rendered
            canvas.drawCircle(canvas.getWidth() / 2, canvas.getHeight() / 2, 50, paint);
        }
    }

    private Rect getMeasuredRect() {
        return new Rect(0, 0, getWidth(), getHeight());
    }
}
