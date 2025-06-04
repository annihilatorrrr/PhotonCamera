package com.particlesdevs.photoncamera.processing;

import android.media.Image;

import com.particlesdevs.photoncamera.processing.render.Parameters;

import java.io.OutputStream;
import java.nio.ByteBuffer;

public class DngCreator {
    private long nativePtr;
    private static final String TAG = "DngCreator";

    // CFA Pattern constants
    public static final int CFA_PATTERN_RGGB = 0;
    public static final int CFA_PATTERN_GRBG = 1;
    public static final int CFA_PATTERN_GBRG = 2;
    public static final int CFA_PATTERN_BGGR = 3;

    // Native methods
    private native long create();
    private native ByteBuffer createDNG(long nativePtr, int width, int height, ByteBuffer rawImageData);
    private native void setOrientation(long nativePtr, int orientation);
    private native void setWhiteLevel(long nativePtr, double whiteLevel);
    private native void setBlackLevel(long nativePtr, short[] blackLevel);
    private native void setColorMatrix1(long nativePtr, double[] matrix);
    private native void setColorMatrix2(long nativePtr, double[] matrix);
    private native void setForwardMatrix1(long nativePtr, double[] matrix);
    private native void setForwardMatrix2(long nativePtr, double[] matrix);
    private native void setCameraCalibration1(long nativePtr, double[] matrix);
    private native void setCameraCalibration2(long nativePtr, double[] matrix);
    private native void setAsShotNeutral(long nativePtr, double[] neutral);
    private native void setAsShotWhiteXY(long nativePtr, double x, double y);
    private native void setAnalogBalance(long nativePtr, double[] balance);
    private native void setCalibrationIlluminant1(long nativePtr, short illuminant);
    private native void setCalibrationIlluminant2(long nativePtr, short illuminant);
    private native void setUniqueCameraModel(long nativePtr, String model);
    private native void setCFAPattern(long nativePtr, int pattern);
    private native void destroy(long nativePtr);

    public DngCreator() {
        nativePtr = create();
        if (nativePtr == 0) {
            throw new RuntimeException("Failed to create DngCreator");
        }
    }

    /**
     * Set the orientation of the DNG image
     * @param orientation The orientation enum
     */
    public void setOrientation(int orientation) {
        if (orientation < 0 || orientation > 8) {
            throw new IllegalArgumentException("Invalid orientation value. Must be between 0 and 8.");
        }
        int rot = 0;
        switch (orientation){
            case 0: // 0 degrees
                rot = 1;
                break;
            case 1: // 90 degrees
                rot = 6;
                break;
            case 2: // 180 degrees
                rot = 3;
                break;
            case 3: // 270 degrees
                rot = 8;
                break;
        }
        setOrientation(nativePtr, rot);
    }

    /**
     * Set the white level for the DNG image
     * @param whiteLevel The white level value (typically 65535 for 16-bit)
     */
    public void setWhiteLevel(double whiteLevel) {
        setWhiteLevel(nativePtr, whiteLevel);
    }

    /**
     * Set the black level for the DNG image
     * @param blackLevel Array of 4 black level values for RGGB pattern
     */
    public void setBlackLevel(short[] blackLevel) {
        if (blackLevel.length != 4) {
            throw new IllegalArgumentException("Black level array must have 4 elements");
        }
        setBlackLevel(nativePtr, blackLevel);
    }

    /**
     * Set the color matrix 1 (for first illuminant)
     * @param matrix 3x3 color matrix as 9-element array
     */
    public void setColorMatrix1(double[] matrix) {
        if (matrix.length != 9) {
            throw new IllegalArgumentException("Color matrix must have 9 elements");
        }
        setColorMatrix1(nativePtr, matrix);
    }

    /**
     * Set the color matrix 2 (for second illuminant)
     * @param matrix 3x3 color matrix as 9-element array
     */
    public void setColorMatrix2(double[] matrix) {
        if (matrix.length != 9) {
            throw new IllegalArgumentException("Color matrix must have 9 elements");
        }
        setColorMatrix2(nativePtr, matrix);
    }

    /**
     * Set the forward matrix 1
     * @param matrix 3x3 forward matrix as 9-element array
     */
    public void setForwardMatrix1(double[] matrix) {
        if (matrix.length != 9) {
            throw new IllegalArgumentException("Forward matrix must have 9 elements");
        }
        setForwardMatrix1(nativePtr, matrix);
    }

    /**
     * Set the forward matrix 2
     * @param matrix 3x3 forward matrix as 9-element array
     */
    public void setForwardMatrix2(double[] matrix) {
        if (matrix.length != 9) {
            throw new IllegalArgumentException("Forward matrix must have 9 elements");
        }
        setForwardMatrix2(nativePtr, matrix);
    }

    /**
     * Set the camera calibration matrix 1
     * @param matrix 3x3 camera calibration matrix as 9-element array
     */
    public void setCameraCalibration1(double[] matrix) {
        if (matrix.length != 9) {
            throw new IllegalArgumentException("Camera calibration matrix must have 9 elements");
        }
        setCameraCalibration1(nativePtr, matrix);
    }

    /**
     * Set the camera calibration matrix 2
     * @param matrix 3x3 camera calibration matrix as 9-element array
     */
    public void setCameraCalibration2(double[] matrix) {
        if (matrix.length != 9) {
            throw new IllegalArgumentException("Camera calibration matrix must have 9 elements");
        }
        setCameraCalibration2(nativePtr, matrix);
    }

    /**
     * Set the as-shot neutral values
     * @param neutral Array of 3 neutral values for RGB
     */
    public void setAsShotNeutral(double[] neutral) {
        if (neutral.length != 3) {
            throw new IllegalArgumentException("As-shot neutral array must have 3 elements");
        }
        setAsShotNeutral(nativePtr, neutral);
    }

    /**
     * Set the as-shot white point coordinates
     * @param x X coordinate
     * @param y Y coordinate
     */
    public void setAsShotWhiteXY(double x, double y) {
        setAsShotWhiteXY(nativePtr, x, y);
    }

    /**
     * Set the analog balance values
     * @param balance Array of 3 analog balance values for RGB
     */
    public void setAnalogBalance(double[] balance) {
        if (balance.length != 3) {
            throw new IllegalArgumentException("Analog balance array must have 3 elements");
        }
        setAnalogBalance(nativePtr, balance);
    }

    /**
     * Set the calibration illuminant 1
     * @param illuminant Illuminant type (e.g., 17 for Standard illuminant A, 21 for D65)
     */
    public void setCalibrationIlluminant1(short illuminant) {
        setCalibrationIlluminant1(nativePtr, illuminant);
    }

    /**
     * Set the calibration illuminant 2
     * @param illuminant Illuminant type (e.g., 17 for Standard illuminant A, 21 for D65)
     */
    public void setCalibrationIlluminant2(short illuminant) {
        setCalibrationIlluminant2(nativePtr, illuminant);
    }

    /**
     * Set the unique camera model name
     * @param model Camera model string
     */
    public void setUniqueCameraModel(String model) {
        setUniqueCameraModel(nativePtr, model);
    }

    /**
     * Set the CFA (Color Filter Array) pattern
     * @param pattern CFA pattern type (use CFA_PATTERN_* constants)
     */
    public void setCFAPattern(int pattern) {
        if (pattern < CFA_PATTERN_RGGB || pattern > CFA_PATTERN_BGGR) {
            throw new IllegalArgumentException("Invalid CFA pattern. Use CFA_PATTERN_* constants.");
        }
        setCFAPattern(nativePtr, pattern);
    }

    public void writeImage(OutputStream outputStream, Image image) {
        ByteBuffer rawImageData = image.getPlanes()[0].getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        writeBuffer(outputStream, rawImageData, width, height);
    }

    public void writeBuffer(OutputStream outputStream, ByteBuffer buffer, int width, int height) {
        ByteBuffer dngData = createDNG(nativePtr, width, height, buffer);
        if (dngData == null) {
            throw new RuntimeException("Failed to create DNG data");
        }

        ByteBuffer softbuffer = ByteBuffer.allocate(dngData.capacity());
        softbuffer.put(dngData);
        softbuffer.position(0);

        try {
            outputStream.write(softbuffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Failed to write DNG data to output stream", e);
        }
    }

    double[] toDouble(float[] array) {
        double[] result = new double[array.length];
        for (int i = 0; i < array.length; i++) {
            result[i] = array[i];
        }
        return result;
    }

    public void setParameters(Parameters parameters) {
        short[] blackLevel = new short[4];
        for (int i = 0; i < 4; i++) {
            blackLevel[i] = (short) parameters.blackLevel[i];
        }
        setBlackLevel(blackLevel);
        setWhiteLevel(parameters.whiteLevel);
        setCalibrationIlluminant1((short) parameters.calibrationIlluminant1);
        setCalibrationIlluminant2((short) parameters.calibrationIlluminant2);
        setColorMatrix1(toDouble(parameters.normalizedColorMatrix1));
        setColorMatrix2(toDouble(parameters.normalizedColorMatrix2));
        setForwardMatrix1(toDouble(parameters.normalizedForwardTransform1));
        setForwardMatrix2(toDouble(parameters.normalizedForwardTransform2));
        setCameraCalibration1(toDouble(parameters.calibrationTransform1));
        setCameraCalibration2(toDouble(parameters.calibrationTransform2));
        setAsShotNeutral(toDouble(parameters.whitePoint));
        setCFAPattern(parameters.cfaPattern);
        setOrientation(parameters.cameraRotation/90);
    }

    /**
     * Clean up native resources
     */
    public void close() {
        if (nativePtr != 0) {
            destroy(nativePtr);
            nativePtr = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    static {
        System.loadLibrary("dngCreator");
    }


}
