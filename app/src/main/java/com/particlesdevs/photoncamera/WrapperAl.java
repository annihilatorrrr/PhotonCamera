package com.particlesdevs.photoncamera;

import java.nio.ByteBuffer;

/** @noinspection JavaJniMissingFunction*/
//PhotonCamera
//Copyright (C) 2020-2021  Eszdman
//https://github.com/eszdman/PhotonCamera
//Using this file when changing the main application package is prohibited
public class WrapperAl {
    static {
        System.loadLibrary("alignmentVectors");
    }
    /**
     * Function to create pointers for image buffers.
     *
     * @param rows   Image rows.
     * @param cols   Image cols.
     * @param frames Image count.
     */
    public static native void init(int rows, int cols, int frames);
    public static native void initAlignments(int rows, int cols, int frames);

    /**
     * Function to load images.
     *
     * @param bufferptr Image buffer.
     */
    public static native void loadFrame(ByteBuffer bufferptr, float Exposure);

    public static native void packImages();
    public static native void loadFrameAlignments(ByteBuffer bufferptr, float Exposure);

    public static native void loadInterpolatedGainMap(ByteBuffer GainMap);

    public static native void outputBuffer(ByteBuffer outputBuffer);
    public static native void processFrame(float NoiseS, float NoiseO,float Smooth, float ElFactor, float BLr,float BLg,float BLb, float WLFactor,
    float wpR,float wpG, float wpB,int CfaPattern);

    public static native void processFrameBayerShift(float NoiseS, float NoiseO, float BLr,float BLg,float BLb, float WLFactor,
                                           float wpR,float wpG, float wpB,int CfaPattern);
    public static native ByteBuffer processFrameAlignments();
}
