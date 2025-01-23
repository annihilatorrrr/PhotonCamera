package com.particlesdevs.photoncamera.processing.opengl.scripts;

import android.graphics.Point;
import android.util.Log;

import com.hunter.library.debug.HunterDebug;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.opengl.GLCoreBlockProcessing;
import com.particlesdevs.photoncamera.processing.opengl.GLDrawParams;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLOneScript;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.GLUtils;
import com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector;
import com.particlesdevs.photoncamera.processing.render.NoiseModeler;
import com.particlesdevs.photoncamera.processing.render.Parameters;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;

import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_MIRRORED_REPEAT;
import static android.opengl.GLES20.GL_NEAREST;

public class PyramidMerging extends GLOneScript {
    public Parameters parameters;
    ArrayList<ImageFrame> images;
    ByteBuffer alignment;
    GLProg glProg;
    GLUtils glUtils;
    public PyramidMerging(Point size,ArrayList<ImageFrame> images, ByteBuffer alignment) {
        super(size, new GLCoreBlockProcessing(size,new GLFormat(GLFormat.DataType.UNSIGNED_16), GLDrawParams.Allocate.Direct),"", "PyramidMerging");
        this.glProg = glOne.glProgram;
        this.images = images;
        this.alignment = alignment;
    }

    float downScalePerLevel = 2.0f;

    @Override
    public void Compile(){}

    GLTexture inputBase;
    GLTexture baseDiff;
    GLTexture base;
    GLTexture avrFrames;
    //GLTexture;
    GLTexture brightMap;
    GLTexture result;
    GLTexture inputAlter;
    GLTexture alignmentTex;
    GLTexture hotPix;
    GLUtils.Pyramid pyramid;

    @Override
    @HunterDebug
    public void Run() {
        glUtils = new GLUtils(glOne.glProcessing);
        Point rawHalf = new Point(parameters.rawSize.x/2,parameters.rawSize.y/2);
        result = new GLTexture(parameters.rawSize,new GLFormat(GLFormat.DataType.UNSIGNED_16,1), null, GL_NEAREST, GL_MIRRORED_REPEAT);
        inputBase = new GLTexture(parameters.rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16,1),images.get(0).buffer, GL_NEAREST, GL_MIRRORED_REPEAT);
        baseDiff = new GLTexture(rawHalf,new GLFormat(GLFormat.DataType.FLOAT_32,4),null,GL_LINEAR,GL_MIRRORED_REPEAT);
        base = new GLTexture(rawHalf,new GLFormat(GLFormat.DataType.FLOAT_32,4),null,GL_LINEAR,GL_MIRRORED_REPEAT);
        avrFrames = new GLTexture(rawHalf,new GLFormat(GLFormat.DataType.FLOAT_32,4),null,GL_LINEAR,GL_MIRRORED_REPEAT);
        //noiseMap = new GLTexture(new Point(rawHalf.x/4,rawHalf.y/4),new GLFormat(GLFormat.DataType.FLOAT_32,4));
        brightMap = new GLTexture(new Point(rawHalf.x/4,rawHalf.y/4),new GLFormat(GLFormat.DataType.FLOAT_16,4));
        hotPix = new GLTexture(rawHalf,new GLFormat(GLFormat.DataType.SIMPLE_8,4));
        float[] blackLevel = parameters.blackLevel;
        int levelcount = (int)(Math.log10(rawHalf.x)/Math.log10(downScalePerLevel))-1;
        if(levelcount <= 0) levelcount = 2;
        //float bl = Math.max(Math.max(parameters.blackLevel[0], parameters.blackLevel[1]), Math.max(parameters.blackLevel[2], parameters.blackLevel[3]));
        glOne.glProgram.setDefine("RAWSIZE",parameters.rawSize);
        glOne.glProgram.setDefine("CFAPATTERN",(int)parameters.cfaPattern);
        //GLUtils glUtils = new GLUtils(glOne.glProcessing);
        int tile = 8;
        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("merge0",true);
        glProg.setVar("whiteLevel",(float)(parameters.whiteLevel));
        glProg.setVar("blackLevel", blackLevel);
        glProg.setVar("exposure", 1.f/images.get(0).pair.layerMpy);
        glProg.setVar("createDiff", 0);
        glProg.setTexture("inTexture",inputBase);
        glProg.setTextureCompute("outTexture",base, true);
        glProg.computeAuto(rawHalf, 1);

        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("merge02",true);
        glProg.setTextureCompute("inTexture",base, false);
        glProg.setTextureCompute("outTexture",brightMap, true);
        glProg.computeAuto(brightMap.mSize, 1);

        /*
        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("merge01",true);
        glProg.setTextureCompute("inTexture",base, false);
        glProg.setTextureCompute("outTexture",noiseMap, true);
        glProg.computeAuto(noiseMap.mSize, 1);

        GLHistogram glHistogram = new GLHistogram(glOne.glProcessing, 64);
        glHistogram.Custom = true;
        glHistogram.resize = 1;
        glHistogram.CustomProgram = "atomicAdd(reds[uint(texColor.r * HISTSIZE)], 1u);" +
                "atomicAdd(greens[uint(texColor.r * HISTSIZE)], uint(texColor.g * 1024.0));" +
                "atomicAdd(blues[uint(texColor.r * HISTSIZE)], uint(texColor.b * 1024.0));" +
                "atomicAdd(alphas[uint(texColor.r * HISTSIZE)], uint(texColor.a * 1024.0));";
        int[][] hist = glHistogram.Compute(noiseMap);
        // print noise map hist
        float[] noise = new float[64];
        float[] brightness = new float[64];
        int cnt = 0;
        for(int i = 0; i < 64; i++){
            int counter = hist[0][i];
            float n = (hist[2][i])/(1.f*1024.f*counter);
            if(counter > 10) {
                noise[cnt] = n;
                brightness[cnt] = (float)(i)/63.f;
                cnt++;
            }
        }
        List<NoiseFitting.DataPoint> data = new ArrayList<>();
        for(int i = 0; i < cnt; i++){
            data.add(new NoiseFitting.DataPoint(brightness[i],noise[i]));
        }
        NoiseFitting.NoiseParameters fitted = NoiseFitting.findParameters(data);
        Log.d(Name, "Fitted parameters: " + fitted.toString());*/
        pyramid = new GLUtils.Pyramid();
        NoiseModeler modeler = parameters.noiseModeler;
        float noiseS = modeler.baseModel[0].first.floatValue() +
                modeler.baseModel[1].first.floatValue() +
                modeler.baseModel[2].first.floatValue();
        float noiseO = modeler.baseModel[0].second.floatValue() +
                modeler.baseModel[1].second.floatValue() +
                modeler.baseModel[2].second.floatValue();
        noiseS /= 3.f;
        noiseO /= 3.f;
        double noisempy = Math.pow(2.0, PhotonCamera.getSettings().mergeStrength);
        noiseS = (float)Math.max(noiseS * noisempy,1e-6f);
        noiseO = (float)Math.max(noiseO * noisempy,1e-6f);
        Point aSize = new Point(parameters.rawSize.x/(2*parameters.tile) + 1, parameters.rawSize.y/(2*parameters.tile) + 1);
        Point border = new Point(16,16);
        inputAlter = new GLTexture(parameters.rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16, 1), null, GL_NEAREST, GL_MIRRORED_REPEAT);
        alignmentTex = new GLTexture(aSize, new GLFormat(GLFormat.DataType.FLOAT_32, 2), alignment, GL_NEAREST, GL_MIRRORED_REPEAT);

        /*FloatBuffer fb = alignment.asFloatBuffer();
        for (int i = 0; i < aSize.x*aSize.y*4*2; i++) {
            Log.d("PyramidMerging", "alignment: " + fb.get(i));
        }*/
        /*
        glProg.setLayout(tile, tile, 1);
        glProg.useAssetProgram("merge5", true);
        glProg.setVar("whiteLevel", (float) (parameters.whiteLevel));
        glProg.setVar("blackLevel", parameters.blackLevel);
        glProg.setVar("start", 1);
        glProg.setVar("last", 0);
        glProg.setVar("noiseS", noiseS);
        glProg.setVar("noiseO", noiseO);
        glProg.setTexture("brTexture", brightMap);
        glProg.setTexture("inTexture", inputBase);
        glProg.setTextureCompute("diffTexture", avrFrames, false);
        glProg.setTextureCompute("outTexture", avrFrames, true);
        glProg.computeAuto(rawHalf, 1);

        for (int f = 1; f < images.size(); f++) {
            glProg.setLayout(tile, tile, 1);
            glProg.useAssetProgram("merge5", true);
            glProg.setVar("whiteLevel", (float) (parameters.whiteLevel));
            glProg.setVar("blackLevel", parameters.blackLevel);
            glProg.setVar("exposure", 1.f/images.get(f).pair.layerMpy);
            glProg.setVar("start", 0);
            glProg.setVar("last", 0);
            glProg.setVar("noiseS", noiseS);
            glProg.setVar("noiseO", noiseO);
            inputAlter.loadData(images.get(0).buffer);
            glProg.setTexture("brTexture", brightMap);
            glProg.setTexture("inTexture", inputAlter);
            glProg.setTextureCompute("diffTexture", avrFrames, false);
            glProg.setTextureCompute("outTexture", avrFrames, true);
            glProg.computeAuto(rawHalf, 1);
        }

        glProg.setLayout(tile, tile, 1);
        glProg.useAssetProgram("merge5", true);
        glProg.setVar("whiteLevel", (float) (parameters.whiteLevel));
        glProg.setVar("blackLevel", parameters.blackLevel);
        glProg.setVar("exposure", 1.f/images.get(0).pair.layerMpy);
        glProg.setVar("start", 0);
        glProg.setVar("last", 1);
        glProg.setVar("noiseS", noiseS);
        glProg.setVar("noiseO", noiseO);
        inputAlter.loadData(images.get(0).buffer);
        glProg.setTexture("brTexture", brightMap);
        glProg.setTexture("inTexture", inputAlter);
        glProg.setTextureCompute("diffTexture", avrFrames, false);
        glProg.setTextureCompute("hotPixTexture", hotPix, true);
        glProg.computeAuto(rawHalf, 1);*/
        // remove first frame
        HashMap<Float, Float> counter = new HashMap<>();
        //counter.put(1.0f,1.0f);

        Log.d("PyramidMerging", "alignment size: " + aSize.x + " " + aSize.y);
        for (int f = 1; f < images.size(); f++) {
            ImageFrame frame = images.get(f);
            float exposure = 1.f/frame.pair.layerMpy;
            //int f = 1;
            Log.d("PyramidMerging", "load:"+frame.pair.curlayer.name() + " " + frame.pair.layerMpy);
            inputAlter.loadData(frame.buffer);
            alignmentTex.loadData(alignment.position((f-1)*(aSize.x*aSize.y*4*2)));
            glProg.setDefine("TILE_AL", 2*parameters.tile);
            glProg.setLayout(tile, tile, 1);
            glProg.useAssetProgram("merge0", true);
            glProg.setVar("whiteLevel", (float) (parameters.whiteLevel));
            glProg.setVar("blackLevel", blackLevel);
            glProg.setVar("exposure", exposure);
            glProg.setVar("createDiff", 1);
            glProg.setVar("noiseS", noiseS);
            glProg.setVar("noiseO", noiseO);
            glProg.setVar("border", border);
            glProg.setTexture("inTexture", inputBase);
            glProg.setTexture("alterTexture", inputAlter);
            glProg.setTexture("alignmentTexture", alignmentTex);
            glProg.setTextureCompute("baseTexture",base, false);
            //glProg.setTextureCompute("avrTexture", avrFrames, false);
            //glProg.setTextureCompute("hotPixTexture", hotPix, false);
            glProg.setTextureCompute("outTexture", baseDiff, true);
            glProg.computeAuto(rawHalf, 1);
            /*
            // apply optical flow

            glProg.setLayout(tile, tile, 1);
            glProg.useAssetProgram("merge03", true);
            glProg.setTextureCompute("diffTexture", baseDiff, false);
            //glProg.setTexture("diffTexture", baseDiff);
            glProg.setTexture("inTexture", inputBase);
            glProg.setTextureCompute("outTexture", diffFlow, true);
            glProg.setVar("whiteLevel", (float) (parameters.whiteLevel));
            glProg.setVar("blackLevel", parameters.blackLevel);
            glProg.setVar("noiseS", noiseS);
            glProg.setVar("noiseO", noiseO);
            glProg.setVar("cfaPattern", parameters.cfaPattern);
            glProg.computeAuto(rawHalf, 1);
            */
            Log.d("PyramidMerging", "create diff");
            GLUtils.Pyramid diff = glUtils.createPyramidStore(levelcount, baseDiff, pyramid);
            Log.d("PyramidMerging", "diff created");

            // do pyramid upscaling
            for (int i = diff.laplace.length - 1; i >= 0; i--) {
                float integralNorm = (float)rawHalf.x * rawHalf.y/(diff.gauss[i+1].mSize.x * diff.gauss[i+1].mSize.y);
                //if(i == diff.laplace.length - 1) integralNorm = 0.f;
                glProg.setLayout(tile, tile, 1);
                glProg.useAssetProgram("merge1", true);
                glProg.setTexture("brTexture", brightMap);
                glProg.setTexture("baseTexture", diff.gauss[i + 1]);
                glProg.setTextureCompute("diffTexture", diff.laplace[i], false);
                glProg.setTextureCompute("outTexture", diff.gauss[i], true);
                //glProg.setVar("noiseS", (float) fitted.S);
                glProg.setVar("noiseS", noiseS);
                //glProg.setVar("noiseO", (float) fitted.O);
                glProg.setVar("noiseO", noiseO);
                glProg.setVar("integralNorm", integralNorm);
                glProg.computeAuto(diff.gauss[i].mSize, 1);
            }

            glProg.setLayout(tile, tile, 1);
            glProg.useAssetProgram("merge11", true);
            glProg.setTextureCompute("inTexture", base, false);
            glProg.setTextureCompute("diffTexture", diff.gauss[0], false);
            glProg.setTextureCompute("outTexture", base, true);
            glProg.setVar("noiseS", noiseS);
            glProg.setVar("noiseO", noiseO);
            //glProg.setVar("weight",  1.0f/(images.size()));
            if(!counter.containsKey(exposure)){
                counter.put(exposure,1.0f);
            }
            //glProg.setVar("weight", 1.0f/(counter.get(exposure)+1.f));
            //glProg.setVar("weight2", 1.0f/(counter.get(exposure)+1.f));
            glProg.setVar("weight", 1.0f/(f+1.f));
            //glProg.setVar("exposure", exposure);
            //glProg.setVar("weight",  1.0f);
            glProg.computeAuto(base.mSize, 1);
        }

        /*
        // Remove residual noise
        GLUtils.Pyramid full = glUtils.createPyramidStore(levelcount, base, pyramid);
        for (int i = full.laplace.length - 1; i >= 0; i--) {
            float integralNorm = (float)base.mSize.x * base.mSize.y/(full.gauss[i+1].mSize.x * full.gauss[i+1].mSize.y);
            glProg.setLayout(tile, tile, 1);
            glProg.useAssetProgram("merge4", true);
            glProg.setTexture("brTexture", brightMap);
            glProg.setTexture("baseTexture", full.gauss[i + 1]);
            glProg.setTextureCompute("diffTexture", full.laplace[i], false);
            //if(i != 0)
                glProg.setTextureCompute("outTexture", full.gauss[i], true);
            //else {
            //    glProg.setTextureCompute("outTexture", base, true);
            //}
            //glProg.setVar("noiseS", (float) fitted.S);
            glProg.setVar("noiseS", noiseS/256);
            //glProg.setVar("noiseO", (float) fitted.O);
            glProg.setVar("noiseO", noiseO/256);
            glProg.setVar("integralNorm", integralNorm);
            glProg.computeAuto(full.gauss[i].mSize, 1);
        }*/
        float[] bl2 = new float[4];
        for (int i = 0; i < 4; i++) {
            bl2[i] = blackLevel[i]*(65535.f / parameters.whiteLevel);
        }
        glProg.setDefine("WHITE_LEVEL", 65535.f);
        glProg.setDefine("BLACK_LEVEL", bl2);
        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("merge2o");
        //glProg.setVar("whiteLevel",65535.f);
        //glProg.setVar("blackLevel", bl2);
        //glProg.setVar("blackLevel", 0.0f);
        glProg.setTexture("inTexture",base);
        //glUtils.convertVec4(outputTex,"in1/2.0");
        //glUtils.SaveProgResult(outputTex.mSize,"gainmap");
        result.BufferLoad();
        glOne.glProcessing.drawBlocksToOutput();
        Output = glOne.glProcessing.mOutBuffer;
    }

    @Override
    public void AfterRun() {
        inputAlter.close();
        inputBase.close();
        baseDiff.close();
        base.close();
        brightMap.close();
        result.close();
        alignmentTex.close();
        //diffFlow.close();
        for (int i = 0; i < images.size(); i++) {
            images.get(i).image.close();
        }

        for (int i = 0; i < pyramid.gauss.length; i++) {
            pyramid.gauss[i].close();
        }

        for (int i = 0; i < pyramid.laplace.length; i++) {
            pyramid.laplace[i].close();
        }
        GLTexture.notClosed();
    }
}
