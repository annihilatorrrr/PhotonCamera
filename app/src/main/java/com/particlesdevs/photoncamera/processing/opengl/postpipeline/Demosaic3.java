package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.FileManager;

public class Demosaic3 extends Node {
    public  Demosaic3() {
        super("", "Demosaic");
    }

    @Override
    public void Compile() {}
    float gradSize = 1.5f;
    float fuseMin = 0.f;
    float fuseMax = 1.f;
    float fuseShift = -0.5f;
    float fuseMpy = 6.0f;
    float greenMin = 1e-8f;
    float greenMax = 1.0f;
    @Override
    public void Run() {
        gradSize = getTuning("GradSize",gradSize);
        fuseMin = getTuning("FuseMin",fuseMin);
        fuseMax = getTuning("FuseMax",fuseMax);
        fuseShift = getTuning("FuseShift",fuseShift);
        fuseMpy = getTuning("FuseMpy",fuseMpy);
        greenMin = getTuning("GreenMin",greenMin);
        greenMax = getTuning("GreenMax",greenMax);
        GLTexture glTexture;
        glTexture = previousNode.WorkingTexture;
        //Gradients
        GLTexture outp;
        int tile = 8;
        startT();
        WorkingTexture = basePipeline.main3;
        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("demosaic/demosaicp0ig",true);
        glProg.setTextureCompute("inTexture", glTexture,false);
        glProg.setTextureCompute("outTexture", WorkingTexture,true);
        glProg.computeManual(WorkingTexture.mSize.x/tile,WorkingTexture.mSize.y/tile,1);
        endT("demosaicp0ig");

        //Colour channels
        startT();
        outp = basePipeline.getMain();
        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("demosaic/demosaicp12ec",true);
        glProg.setTextureCompute("inTexture",glTexture, false);
        glProg.setTextureCompute("igTexture",basePipeline.main3, false);
        glProg.setTextureCompute("outTexture",outp, true);
        glProg.computeManual(WorkingTexture.mSize.x/tile,WorkingTexture.mSize.y/tile,1);
        endT("demosaicp12ec");

        startT();
        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("demosaic/demosaicp12fc",true);
        glProg.setTextureCompute("inTexture",glTexture, false);
        glProg.setTextureCompute("igTexture",basePipeline.main3, false);
        glProg.setTextureCompute("greenTexture",outp, false);
        glProg.setTextureCompute("outTexture",outp, true);
        glProg.computeManual(WorkingTexture.mSize.x/tile,WorkingTexture.mSize.y/tile,1);
        endT("demosaicp12fc");
        //glProg.drawBlocks(WorkingTexture);

        startT();
        WorkingTexture = basePipeline.main3;
        glProg.setDefine("greenmin",greenMin);
        glProg.setDefine("greenmax",greenMax);
        glProg.setLayout(tile,tile,1);
        //glProg.useFileProgram(FileManager.sPHOTON_TUNING_DIR + "demosaicp2ec.glsl",true);
        glProg.useAssetProgram("demosaic/demosaicp2ed",true);
        glProg.setTextureCompute("inTexture", glTexture,false);
        glProg.setTextureCompute("greenTexture", outp,false);
        glProg.setTextureCompute("igTexture", basePipeline.main3,false);
        glProg.setTextureCompute("outTexture", WorkingTexture,true);
        glProg.setVar("neutral", basePipeline.mParameters.whitePoint[0], basePipeline.mParameters.whitePoint[1], basePipeline.mParameters.whitePoint[1], basePipeline.mParameters.whitePoint[2]);
        //glProg.setVar("neutral", 1.f, 1.f, 1.f, 1.f);
        glProg.computeManual(WorkingTexture.mSize.x/tile,WorkingTexture.mSize.y/tile,1);
        glProg.close();
        endT("demosaicp2ec");
    }
}
