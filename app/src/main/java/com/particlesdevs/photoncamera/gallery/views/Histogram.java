package com.particlesdevs.photoncamera.gallery.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.particlesdevs.photoncamera.processing.opengl.GLImage;
import com.particlesdevs.photoncamera.processing.opengl.scripts.GLHistogram;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class Histogram extends View {
    private final Paint wallPaint;
    private final PorterDuffXfermode porterDuffXfermode = new PorterDuffXfermode(PorterDuff.Mode.ADD);
    private HistogramLoadingListener sHistogramLoadingListener;
    private HistogramModel histogramModel;
    private final Path wallPath = new Path();
    GLHistogram glHistogram;
    ExecutorService histogramExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HistogramThread");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    public Histogram(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        wallPaint = new Paint();
        histogramExecutor.execute(() -> {
            glHistogram = new GLHistogram();
        });
    }

    public HistogramModel analyze(Bitmap bitmap) {
        int size = 256;
        int[][][] colorsMap = new int[1][4][256];
        int maxY = 0;
        //colorsMap = HistogramRs.getHistogram(bitmap);
        AtomicBoolean compute = new AtomicBoolean(false);
        histogramExecutor.execute(() -> {
            GLImage glImage = new GLImage(bitmap);
            colorsMap[0] = glHistogram.Compute(glImage);
            glImage.close();
            compute.set(true);
        });
        while (!compute.get()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Log.d("Histogram", "Thread interrupted:"+Log.getStackTraceString(e));
            }
        }
        //Find max
        for (int i = 0; i < size; i++) {
            maxY = Math.max(maxY, colorsMap[0][0][i]);
            maxY = Math.max(maxY, colorsMap[0][1][i]);
            maxY = Math.max(maxY, colorsMap[0][2][i]);
        }
        return new HistogramModel(size, colorsMap[0], maxY);
    }

    public void setHistogramLoadingListener(HistogramLoadingListener histogramLoadingListener) {
        this.sHistogramLoadingListener = histogramLoadingListener;
    }

    public void setHistogramModel(HistogramModel histogramModel) {
        this.histogramModel = histogramModel;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        //super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();

        wallPaint.setAntiAlias(true);
        wallPaint.setStyle(Paint.Style.STROKE);
        wallPaint.setARGB(100, 255, 255, 255);
        canvas.drawRect(0, 0, width, height, wallPaint);
        canvas.drawLine(width / 3.f, 0, width / 3.f, height, wallPaint);
        canvas.drawLine(2.f * width / 3.f, 0, 2.f * width / 3.f, height, wallPaint);

        if (sHistogramLoadingListener != null) {
            sHistogramLoadingListener.isLoading(true);
        }
        if (histogramModel == null) {
            return;
        }

        float xInterval = ((float) getWidth() / ((float) histogramModel.getSize() + 1));
        for (int i = 0; i < 3; i++) {
            if (i == 0) {
                //wallpaint.setColor(0xFF0700);
                wallPaint.setARGB(0xFF, 0x19, 0x24, 0xB1);
            } else if (i == 1) {
                //wallpaint.setColor(0x1924B1);
                wallPaint.setARGB(0xFF, 0x00, 0xC9, 0x0D);
            } else {
                //wallpaint.setColor(0x00C90D);
                wallPaint.setARGB(0xFF, 0xFF, 0x07, 0x00);
            }
            wallPaint.setXfermode(porterDuffXfermode);
            wallPaint.setStyle(Paint.Style.FILL);
            wallPath.reset();
            wallPath.moveTo(0, height);
            for (int j = 0; j < histogramModel.getSize(); j++) {
                float value = (((float) histogramModel.getColorsMap()[i][j]) * ((float) (height) / histogramModel.getMaxY()));
                wallPath.lineTo(j * xInterval, height - value);
            }
            wallPath.lineTo(histogramModel.getSize() * xInterval, height);
            //wallPath.lineTo(histogramModel.getSize() * offset, height);
            canvas.drawPath(wallPath, wallPaint);
        }
        if (sHistogramLoadingListener != null) {
            sHistogramLoadingListener.isLoading(false);
        }
    }

    public interface HistogramLoadingListener {
        void isLoading(boolean loading);
    }

    /**
     * Simple data class that stores the data required to draw histogram
     */
    public static class HistogramModel {
        private final int size;
        private final int maxY;
        private final int[][] colorsMap;

        public HistogramModel(int size, int[][] colorsMap, int maxY) {
            this.size = size;
            this.maxY = maxY;
            this.colorsMap = colorsMap;
        }

        public int getSize() {
            return size;
        }

        public int getMaxY() {
            return maxY;
        }

        public int[][] getColorsMap() {
            return colorsMap;
        }

    }
}
