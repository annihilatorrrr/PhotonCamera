package com.eszdman.photoncamera.Parameters;

import com.eszdman.photoncamera.api.CameraController;
import com.eszdman.photoncamera.api.CameraFragment;

import java.util.Locale;

public class ExposureIndex {
    public static final long sec = 1000000000;

    public static double index() {
        long exposureTime = CameraController.GET().mPreviewExposuretime;
        int iso = CameraController.GET().mPreviewIso;
        double time = (double) (exposureTime) / sec;
        return iso * time;
    }
    public static double time2sec(long in){
        return ((double)in)/sec;
    }
    public static String sec2string(double in){
        if(in > 1.0) return String.format(Locale.US,"%.2f", in);
        else {
            in = 1.0/in;
            return "1/"+String.format(Locale.US,"%.2f", in);
        }
    }
    public static long sec2time(double in){
        return (long)(in*sec);
    }
}
