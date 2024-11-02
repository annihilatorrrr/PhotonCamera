package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

public class Demosaic3 extends Node {
    public Demosaic3() {
        super("", "Demosaic");
    }

    @Override
    public void Compile() {}
    float gradSize = 1.5f;
    float fuseMin = 0.f;
    float fuseMax = 1.f;
    float fuseShift = -0.5f;
    float fuseMpy = 6.0f;
    @Override
    public void Run() {
        gradSize = getTuning("GradSize",gradSize);
        fuseMin = getTuning("FuseMin",fuseMin);
        fuseMax = getTuning("FuseMax",fuseMax);
        fuseShift = getTuning("FuseShift",fuseShift);
        fuseMpy = getTuning("FuseMpy",fuseMpy);
        GLTexture glTexture;
        glTexture = previousNode.WorkingTexture;
        //Gradients
        GLTexture outp;
        int tile = 8;
        WorkingTexture = basePipeline.main3;
        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("demosaicp0ig",true);
        glProg.setTextureCompute("inTexture", glTexture,false);
        glProg.setTextureCompute("outTexture", WorkingTexture,true);
        glProg.computeManual(WorkingTexture.mSize.x/tile,WorkingTexture.mSize.y/tile,1);


        //Colour channels
        startT();
        outp = basePipeline.getMain();
        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("demosaicp12ec",true);
        glProg.setTextureCompute("inTexture",glTexture, false);
        glProg.setTextureCompute("igTexture",basePipeline.main3, false);
        glProg.setTextureCompute("outTexture",outp, true);
        glProg.computeManual(WorkingTexture.mSize.x/tile,WorkingTexture.mSize.y/tile,1);

        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("demosaicp12fc",true);
        glProg.setTextureCompute("inTexture",glTexture, false);
        glProg.setTextureCompute("igTexture",basePipeline.main3, false);
        glProg.setTextureCompute("greenTexture",outp, false);
        glProg.setTextureCompute("outTexture",outp, true);
        glProg.computeManual(WorkingTexture.mSize.x/tile,WorkingTexture.mSize.y/tile,1);
        //glProg.drawBlocks(WorkingTexture);

        WorkingTexture = basePipeline.main3;
        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("demosaicp2ec",true);
        glProg.setTextureCompute("inTexture", glTexture,false);
        glProg.setTextureCompute("greenTexture", outp,false);
        glProg.setTextureCompute("igTexture", basePipeline.main3,false);
        glProg.setTextureCompute("outTexture", WorkingTexture,true);
        glProg.computeManual(WorkingTexture.mSize.x/tile,WorkingTexture.mSize.y/tile,1);
        glProg.close();
        endT("Demosaic2");
    }
}
