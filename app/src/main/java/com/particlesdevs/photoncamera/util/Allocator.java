package com.particlesdevs.photoncamera.util;

import java.nio.ByteBuffer;
public class Allocator{
    static {
        System.loadLibrary("allocator");
    }
    public native static ByteBuffer allocate(int capacity);

    public native static void free(ByteBuffer buffer);
}
