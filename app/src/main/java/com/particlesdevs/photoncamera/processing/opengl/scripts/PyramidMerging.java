package com.particlesdevs.photoncamera.processing.opengl.scripts;

import android.graphics.Point;
import android.util.Log;

import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.opengl.GLCoreBlockProcessing;
import com.particlesdevs.photoncamera.processing.opengl.GLDrawParams;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLOneScript;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.GLUtils;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.util.NoiseFitting;

import java.util.ArrayList;
import java.util.List;

import static android.opengl.GLES20.GL_MIRRORED_REPEAT;
import static android.opengl.GLES20.GL_NEAREST;

public class PyramidMerging extends GLOneScript {
    public Parameters parameters;
    ArrayList<ImageFrame> images;
    GLProg glProg;
    GLUtils glUtils;
    int levels = 4;
    public PyramidMerging(Point size,ArrayList<ImageFrame> images) {
        super(size, new GLCoreBlockProcessing(size,new GLFormat(GLFormat.DataType.UNSIGNED_16), GLDrawParams.Allocate.Direct),"", "PyramidMerging");
        this.glProg = glOne.glProgram;
        this.images = images;
    }

    float downScalePerLevel = 2.0f;

    @Override
    public void Compile(){}

    public GLUtils.Pyramid createPyramid(int levels, GLTexture input, GLUtils.Pyramid pyramid){
        pyramid.levels = levels;
        pyramid.step = 2.0;
        GLTexture[] downscaled = new GLTexture[levels];
        downscaled[0] = input;

        //GLTexture[] upscale = new GLTexture[downscaled.length - 1];
        pyramid.sizes = new Point[downscaled.length];
        pyramid.sizes[0] = new Point(input.mSize);
        double step = 2.0;
        for (int i = 1; i < downscaled.length; i++) {
            //if(autostep && i < 2) step = 2; else step = 4;
            //downscaled[i] = gaussdown(downscaled[i - 1],step);
            Point insize = downscaled[i-1].mSize;
            int sizex = (int)(insize.x/step);
            int sizey = (int)(insize.y/step);
            sizex = Math.max(1,sizex);
            sizey = Math.max(1,sizey);
            if (downscaled[i] == null){
                downscaled[i] = new GLTexture(new Point(sizex,sizey),downscaled[i-1].mFormat);
            }
            glUtils.interpolate(downscaled[i - 1],downscaled[i]);
            GLTexture old = downscaled[i];
            downscaled[i] = glUtils.blursmall(downscaled[i],3,1.4);
            old.close();
            //downscaled[i] = medianDown(downscaled[i-1],new GLTexture(new Point(sizex,sizey),downscaled[i-1].mFormat), (float) step);
            pyramid.sizes[i] = new Point((int)(pyramid.sizes[i-1].x/step),(int)(pyramid.sizes[i-1].y/step));
            Log.d("Pyramid","downscale:"+pyramid.sizes[i]);
        }
        /*for (int i = 0; i < upscale.length; i++) {
            upscale[i] = (glUtils.interpolate(downscaled[i + 1],pyramid.sizes[i]));
            //upscale[i] = downscaled[i+1];
            Log.d("Pyramid","upscale:"+pyramid.sizes[i]);
            //Log.d("Pyramid","point:"+pyramid.sizes[i]+" after:"+upscale[i].mSize);
        }*/

        glProg.useUtilProgram("pyramiddiff",false);
        GLTexture[] diff = new GLTexture[downscaled.length - 1];
        for (int i = 0; i < diff.length; i++) {
            glProg.setTexture("target", downscaled[i]);
            glProg.setTexture("base", downscaled[i + 1]);
            glProg.setVar("size",pyramid.sizes[i]);
            glProg.setVar("size2", downscaled[i + 1].mSize);
            //glProg.setTexture("base", downscaled[i]);
            //glProg.setTexture("target", upscale[i]);
            diff[i] = new GLTexture(pyramid.sizes[i],downscaled[i + 1].mFormat);
            glProg.drawBlocks(diff[i]);
            Log.d("Pyramid","diff:"+diff[i].mSize+" downscaled:"+downscaled[i].mSize);
        }
        pyramid.gauss = downscaled;
        pyramid.laplace = diff;
        return pyramid;
    }

    @Override
    public void Run() {
        glUtils = new GLUtils(glOne.glProcessing);
        Point rawHalf = new Point(parameters.rawSize.x/2,parameters.rawSize.y/2);
        GLTexture inputBase = new GLTexture(parameters.rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16,1),images.get(0).buffer, GL_NEAREST, GL_MIRRORED_REPEAT);
        GLTexture baseDiff = new GLTexture(rawHalf,new GLFormat(GLFormat.DataType.FLOAT_32,4));
        GLTexture base = new GLTexture(rawHalf,new GLFormat(GLFormat.DataType.FLOAT_32,4));
        GLTexture noiseMap = new GLTexture(new Point(rawHalf.x/4,rawHalf.y/4),new GLFormat(GLFormat.DataType.FLOAT_32,4));
        GLTexture result = new GLTexture(parameters.rawSize,new GLFormat(GLFormat.DataType.UNSIGNED_16,1));
        int levelcount = (int)(Math.log10(rawHalf.x)/Math.log10(downScalePerLevel));
        if(levelcount <= 0) levelcount = 2;
        glOne.glProgram.setDefine("RAWSIZE",parameters.rawSize);
        glOne.glProgram.setDefine("CFAPATTERN",(int)parameters.cfaPattern);
        //GLUtils glUtils = new GLUtils(glOne.glProcessing);
        int tile = 8;
        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("merge0",true);
        glProg.setVar("whiteLevel",(float)(parameters.whiteLevel));
        glProg.setVar("createDiff", 0);
        glProg.setTexture("inTexture",inputBase);
        glProg.setTextureCompute("outTexture",base, true);
        glProg.computeAuto(rawHalf, 1);

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
        Log.d(Name, "Fitted parameters: " + fitted.toString());

        GLTexture inputAlter = new GLTexture(parameters.rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16,1),images.get(1).buffer, GL_NEAREST, GL_MIRRORED_REPEAT);

        glProg.useAssetProgram("merge0",true);
        glProg.setVar("whiteLevel",(float)(parameters.whiteLevel));
        glProg.setVar("createDiff", 1);
        glProg.setTexture("inTexture",inputBase);
        glProg.setTexture("alterTexture",inputAlter);
        glProg.setTextureCompute("outTexture",baseDiff, true);
        glProg.computeAuto(rawHalf, 1);

        GLUtils.Pyramid diff = glUtils.createPyramid(levelcount,downScalePerLevel, baseDiff);


        glProg.useAssetProgram("merge2o");
        glProg.setVar("whiteLevel",65535.f);
        glProg.setTexture("inTexture",base);
        //glUtils.convertVec4(outputTex,"in1/2.0");
        //glUtils.SaveProgResult(outputTex.mSize,"gainmap");
        result.BufferLoad();
        glOne.glProcessing.drawBlocksToOutput();
        inputBase.close();
        baseDiff.close();
        result.close();
        Output = glOne.glProcessing.mOutBuffer;
    }
}
