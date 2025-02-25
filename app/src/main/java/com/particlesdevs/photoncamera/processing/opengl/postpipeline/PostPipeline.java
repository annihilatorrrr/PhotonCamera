package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.util.Log;

import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.opengl.GLBasePipeline;
import com.particlesdevs.photoncamera.processing.opengl.GLCoreBlockProcessing;
import com.particlesdevs.photoncamera.processing.opengl.GLDrawParams;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLImage;
import com.particlesdevs.photoncamera.processing.opengl.GLInterface;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.parameters.ResolutionSolution;
import com.particlesdevs.photoncamera.processing.render.NoiseModeler;
import com.particlesdevs.photoncamera.processing.render.Parameters;

import java.nio.ByteBuffer;
import java.util.ArrayList;

public class PostPipeline extends GLBasePipeline {
    public ByteBuffer stackFrame;
    public ByteBuffer lowFrame;
    public ByteBuffer highFrame;
    public GLTexture FusionMap;
    public GLTexture GainMap;
    public ArrayList<Bitmap> debugData = new ArrayList<>();
    public ArrayList<ImageFrame> SAGAIN;
    public Point cropSize;
    public float[] analyzedBL = new float[]{0.f, 0.f, 0.f};
    float regenerationSense = 1.f;
    float totalGain = 1.f;
    float AecCorr = 1.f;
    float fusionGain = 1.f;
    float softLight = 1.f;

    public int getRotation() {
        int rotation = mParameters.cameraRotation;
        String TAG = "ParseExif";
        Log.d(TAG, "Gravity rotation:" + PhotonCamera.getGravity().getRotation());
        Log.d(TAG, "Sensor rotation:" + PhotonCamera.getCaptureController().mSensorOrientation);
        return rotation;
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private Point getRotatedCoords(Point in) {
        switch (getRotation()) {
            case 0:
            case 180:
                return in;
            case 90:
            case 270:
                return new Point(in.y, in.x);
        }
        return in;
    }

    float constShift = 0.0f;

    public Bitmap Run(ByteBuffer inBuffer, Parameters parameters) {
        mParameters = parameters;
        mSettings = PhotonCamera.getSettings();
        workSize = new Point(mParameters.rawSize.x, mParameters.rawSize.y);
        NoiseModeler modeler = mParameters.noiseModeler;
        noiseS = modeler.computeModel[0].first.floatValue() +
                modeler.computeModel[1].first.floatValue() +
                modeler.computeModel[2].first.floatValue();
        noiseO = modeler.computeModel[0].second.floatValue() +
                modeler.computeModel[1].second.floatValue() +
                modeler.computeModel[2].second.floatValue();
        noiseS /= 3.f;
        noiseO /= 3.f;
        double noisempy = Math.pow(2.0, mSettings.noiseRstr + constShift);
        Log.d("PostPipeline", "noisempy:" + noisempy);
        noiseS *= noisempy;
        noiseO *= noisempy;
        Log.d("PostPipeline", "NoiseS:" + noiseS + "\n" + "NoiseO:" + noiseO);
        if (!PhotonCamera.getSettings().hdrxNR) {
            noiseO = 0.f;
            noiseS = 0.f;
        }
        noiseO = Math.max(noiseO, Float.MIN_NORMAL);
        noiseS = Math.max(noiseS, Float.MIN_NORMAL);
        Point rawSliced = parameters.rawSize;
        cropSize = new Point(parameters.rawSize);
        if (PhotonCamera.getSettings().aspect169) {
            if (rawSliced.x > rawSliced.y) {
                rawSliced = new Point(rawSliced.x, rawSliced.x * 9 / 16);
            } else {
                rawSliced = new Point(rawSliced.y * 9 / 16, rawSliced.y);
            }
            cropSize =  new Point(rawSliced);
        }
        Point rotatedSize = getRotatedCoords(rawSliced);
        if (PhotonCamera.getSettings().energySaving || mParameters.rawSize.x * mParameters.rawSize.y < ResolutionSolution.smallRes) {
            GLDrawParams.TileSize = 8;
        } else {
            GLDrawParams.TileSize = 256;
        }
        GLFormat format = new GLFormat(GLFormat.DataType.SIMPLE_8, 4);
        GLImage output = new GLImage(rotatedSize, format);

        GLCoreBlockProcessing glproc = new GLCoreBlockProcessing(rotatedSize, output, format, GLDrawParams.Allocate.Heap);
        glint = new GLInterface(glproc);
        stackFrame = inBuffer;
        glint.parameters = parameters;

        BuildDefaultPipeline();
        Bitmap res = runAll().getBufferedImage();
        GLTexture.closeAll();
        return res;
    }

    private void BuildDefaultPipeline() {
        boolean nightMode = PhotonCamera.getSettings().selectedMode == CameraMode.NIGHT;
        add(new Bayer2Float());
        add(new ExposureFusionBayer2());
        switch (PhotonCamera.getSettings().cfaPattern) {
            case -2: {
                add(new DemosaicQUAD());
                break;
            }
            case 4: {
                add(new MonoDemosaic());
                break;
            }
            default: {
                if (nightMode)
                    add(new HotPixelFilter());
                //if(PhotonCamera.getSettings().hdrxNR) {
                //add(new ESD3DBayerCS());
                //}

                if (PhotonCamera.getSettings().hdrxNR) {

                    //add(new BayerFilter());
                    if (nightMode) {
                        add(new BayerConcat(true));
                        add(new BayerFilter());
                        add(new BayerConcat(false));
                    }
                    //add(new BayerMoire());

                }

                if(mSettings.alignAlgorithm != 2) {
                    add(new Demosaic3());
                }
                if (PhotonCamera.getSettings().hdrxNR) {
                    add(new ESD3D(true));
                }
                //add(new ImpulsePixelFilter());
                break;
            }
        }
        /*
         * * * All filters after demosaicing * * *
         */

        //if (PhotonCamera.getSettings().hdrxNR) {
            //if (nightMode)
            //    add(new Wavelet());
            //add(new ESD3D(true));
            //add(new ESD3D(true));
        //}

        //add(new AWB());
        add(new Equalization());

        add(new Initial());


        //add(new GlobalToneMapping());

        add(new CaptureSharpening());

        add(new CorrectingFlow());

        //add(new ChromaticFlow());

        add(new Sharpen2());
        //add(new Sharpen("sharpen33"));

        add(new RotateWatermark(getRotation()));
    }
}
