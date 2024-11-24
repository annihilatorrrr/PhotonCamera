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
import com.particlesdevs.photoncamera.processing.render.NoiseModeler;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.util.NoiseFitting;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_MIRRORED_REPEAT;
import static android.opengl.GLES20.GL_NEAREST;

public class PyramidMerging extends GLOneScript {
    public Parameters parameters;
    ArrayList<ImageFrame> images;
    ByteBuffer alignment;
    GLProg glProg;
    GLUtils glUtils;
    int levels = 4;
    public PyramidMerging(Point size,ArrayList<ImageFrame> images, ByteBuffer alignment) {
        super(size, new GLCoreBlockProcessing(size,new GLFormat(GLFormat.DataType.UNSIGNED_16), GLDrawParams.Allocate.Direct),"", "PyramidMerging");
        this.glProg = glOne.glProgram;
        this.images = images;
        this.alignment = alignment;
    }

    float downScalePerLevel = 2.0f;

    @Override
    public void Compile(){}

    @HunterDebug
    public GLUtils.Pyramid createPyramid(int levels, GLTexture input, GLUtils.Pyramid pyramid){
        pyramid.levels = levels;
        pyramid.step = 2.0;
        if (pyramid.gauss == null){
            pyramid.gauss = new GLTexture[levels];
        }
        pyramid.gauss[0] = input;

        //GLTexture[] upscale = new GLTexture[downscaled.length - 1];
        pyramid.sizes = new Point[pyramid.gauss.length];
        pyramid.sizes[0] = new Point(input.mSize);
        double step = 2.0;
        for (int i = 1; i < pyramid.gauss.length; i++) {
            //if(autostep && i < 2) step = 2; else step = 4;
            //downscaled[i] = gaussdown(downscaled[i - 1],step);
            Point insize = pyramid.gauss[i-1].mSize;
            int sizex = (int)(insize.x/step);
            int sizey = (int)(insize.y/step);
            sizex = Math.max(1,sizex);
            sizey = Math.max(1,sizey);
            if (pyramid.gauss[i] == null){
                pyramid.gauss[i] = new GLTexture(new Point(sizex,sizey),pyramid.gauss[i-1].mFormat);
            }
            glUtils.interpolate(pyramid.gauss[i - 1],pyramid.gauss[i]);
            //GLTexture old = downscaled[i];
            //downscaled[i] = glUtils.blursmall(downscaled[i],3,1.4);
            //old.close();
            //downscaled[i] = medianDown(downscaled[i-1],new GLTexture(new Point(sizex,sizey),downscaled[i-1].mFormat), (float) step);
            pyramid.sizes[i] = new Point((int)(pyramid.sizes[i-1].x/step),(int)(pyramid.sizes[i-1].y/step));
            //Log.d("Pyramid","downscale:"+pyramid.sizes[i]);
        }

        glProg.useUtilProgram("pyramiddiff",false);
        if (pyramid.laplace == null) pyramid.laplace = new GLTexture[pyramid.gauss.length - 1];
        for (int i = 0; i < pyramid.laplace.length; i++) {
            glProg.setTexture("target", pyramid.gauss[i]);
            glProg.setTexture("base", pyramid.gauss[i + 1]);
            glProg.setVar("size",pyramid.sizes[i]);
            glProg.setVar("size2", pyramid.gauss[i + 1].mSize);
            //glProg.setTexture("base", downscaled[i]);
            //glProg.setTexture("target", upscale[i]);
            if (pyramid.laplace[i] == null)
                pyramid.laplace[i] = new GLTexture(pyramid.sizes[i],pyramid.gauss[i + 1].mFormat);
            glProg.drawBlocks(pyramid.laplace[i]);
            //Log.d("Pyramid","diff:"+pyramid.laplace[i].mSize+" downscaled:"+pyramid.gauss[i].mSize);
        }
        return pyramid;
    }

    @Override
    @HunterDebug
    public void Run() {
        glUtils = new GLUtils(glOne.glProcessing);
        Point rawHalf = new Point(parameters.rawSize.x/2,parameters.rawSize.y/2);
        GLTexture inputBase = new GLTexture(parameters.rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16,1),images.get(0).buffer, GL_NEAREST, GL_MIRRORED_REPEAT);
        GLTexture baseDiff = new GLTexture(rawHalf,new GLFormat(GLFormat.DataType.FLOAT_16,4));
        GLTexture base = new GLTexture(rawHalf,new GLFormat(GLFormat.DataType.FLOAT_32,4));
        GLTexture diffFlow = new GLTexture(rawHalf,new GLFormat(GLFormat.DataType.FLOAT_16,4),null,GL_LINEAR,GL_MIRRORED_REPEAT);
        //GLTexture noiseMap = new GLTexture(new Point(rawHalf.x/4,rawHalf.y/4),new GLFormat(GLFormat.DataType.FLOAT_32,4));
        GLTexture brightMap = new GLTexture(new Point(rawHalf.x/4,rawHalf.y/4),new GLFormat(GLFormat.DataType.FLOAT_16,4));
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
        GLUtils.Pyramid pyramid = new GLUtils.Pyramid();
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
        Point aSize = new Point(parameters.rawSize.x/16 + 1, parameters.rawSize.y/16 + 1);
        Point border = new Point(16,16);
        GLTexture inputAlter = new GLTexture(parameters.rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16, 1), null, GL_NEAREST, GL_MIRRORED_REPEAT);
        GLTexture alignmentTex = new GLTexture(aSize, new GLFormat(GLFormat.DataType.FLOAT_32, 2), alignment, GL_NEAREST, GL_MIRRORED_REPEAT);
        /*FloatBuffer fb = alignment.asFloatBuffer();
        for (int i = 0; i < aSize.x*aSize.y*4*2; i++) {
            Log.d("PyramidMerging", "alignment: " + fb.get(i));
        }*/
        for (int f = 1; f < images.size(); f++) {
            //int f = 1;
            inputAlter.loadData(images.get(f).buffer);
            alignmentTex.loadData(alignment.position((f-1)*(aSize.x*aSize.y*4*2)));

            glProg.setLayout(tile, tile, 1);
            glProg.useAssetProgram("merge0", true);
            glProg.setVar("whiteLevel", (float) (parameters.whiteLevel));
            glProg.setVar("createDiff", 1);
            glProg.setVar("noiseS", noiseS);
            glProg.setVar("noiseO", noiseO);
            glProg.setVar("border", border);
            glProg.setTexture("inTexture", inputBase);
            glProg.setTexture("alterTexture", inputAlter);
            glProg.setTexture("alignmentTexture", alignmentTex);
            glProg.setTextureCompute("outTexture", baseDiff, true);
            glProg.computeAuto(rawHalf, 1);

            // apply optical flow
            glProg.setLayout(tile, tile, 1);
            glProg.useAssetProgram("merge03", true);
            glProg.setTextureCompute("diffTexture", baseDiff, false);
            //glProg.setTexture("diffTexture", baseDiff);
            glProg.setTexture("inTexture", inputBase);
            glProg.setTextureCompute("outTexture", diffFlow, true);
            glProg.setVar("whiteLevel", (float) (parameters.whiteLevel));
            glProg.setVar("noiseS", noiseS);
            glProg.setVar("noiseO", noiseO);
            glProg.computeAuto(rawHalf, 1);

            GLUtils.Pyramid diff = createPyramid(levelcount, diffFlow, pyramid);

            // do pyramid upscaling
            for (int i = diff.laplace.length - 1; i >= 0; i--) {
                float integralNorm = (float)diffFlow.mSize.x * diffFlow.mSize.y/(diff.gauss[i+1].mSize.x * diff.gauss[i+1].mSize.y);
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
            glProg.setVar("weight",  1.0f/(images.size()));
            //glProg.setVar("weight", 1.0f/(f+1.f));
            //glProg.setVar("weight",  1.0f);
            glProg.computeAuto(base.mSize, 1);

        }
        inputAlter.close();

        for (int i = 1; i < images.size(); i++) {
            images.get(i).image.close();
        }

        for (int i = 0; i < pyramid.gauss.length; i++) {
            pyramid.gauss[i].close();
        }

        for (int i = 0; i < pyramid.laplace.length; i++) {
            pyramid.laplace[i].close();
        }


        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("merge2o");
        glProg.setVar("whiteLevel",65535.f);
        glProg.setTexture("inTexture",base);
        //glUtils.convertVec4(outputTex,"in1/2.0");
        //glUtils.SaveProgResult(outputTex.mSize,"gainmap");
        result.BufferLoad();
        glOne.glProcessing.drawBlocksToOutput();
        inputBase.close();
        baseDiff.close();
        base.close();
        brightMap.close();
        result.close();
        alignmentTex.close();
        diffFlow.close();
        Output = glOne.glProcessing.mOutBuffer;
        GLTexture.notClosed();
    }
}
