package com.particlesdevs.photoncamera.processing.opengl.scripts;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.opengl.GLCoreBlockProcessing;
import com.particlesdevs.photoncamera.processing.opengl.GLDrawParams;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLOneScript;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.GLUtils;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.util.BufferUtils;

import java.util.ArrayList;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_MIRRORED_REPEAT;
import static android.opengl.GLES20.GL_NEAREST;

public class PyramidMerging extends GLOneScript {
    public Parameters parameters;
    ArrayList<ImageFrame> images;
    GLProg glProg;
    public PyramidMerging(Point size,ArrayList<ImageFrame> images) {
        super(size, new GLCoreBlockProcessing(size,new GLFormat(GLFormat.DataType.UNSIGNED_16), GLDrawParams.Allocate.Direct),"", "PyramidMerging");
        this.glProg = glOne.glProgram;
        this.images = images;
    }

    float downScalePerLevel = 2.0f;

    @Override
    public void Compile(){}

    @Override
    public void Run() {
        GLUtils glUtils = new GLUtils(glOne.glProcessing);
        Point rawHalf = new Point(parameters.rawSize.x/2,parameters.rawSize.y/2);
        GLTexture input = new GLTexture(parameters.rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16,1),images.get(0).buffer, GL_NEAREST, GL_MIRRORED_REPEAT);
        GLTexture baseTex = new GLTexture(rawHalf,new GLFormat(GLFormat.DataType.FLOAT_32,4));
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

        glProg.setTexture("inTexture",input);
        glProg.setTextureCompute("outTexture",baseTex, true);
        glProg.computeAuto(rawHalf, 1);

        //GLUtils.Pyramid baseFrame = glUtils.createPyramid(levelcount,downScalePerLevel, baseTex);

        glProg.useAssetProgram("merge2o");
        glProg.setVar("whiteLevel",65535.f);
        glProg.setTexture("inTexture",baseTex);
        //glUtils.convertVec4(outputTex,"in1/2.0");
        //glUtils.SaveProgResult(outputTex.mSize,"gainmap");
        result.BufferLoad();
        glOne.glProcessing.drawBlocksToOutput();
        input.close();
        baseTex.close();
        result.close();
        Output = glOne.glProcessing.mOutBuffer;
    }
}
