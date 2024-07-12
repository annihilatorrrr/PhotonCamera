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

        //Colour channels
        startT();
        glProg.useAssetProgram("demosaicp12e");
        glProg.setTexture("bayerTexture",glTexture);
        WorkingTexture = basePipeline.main3;
        glProg.drawBlocks(WorkingTexture);
        glProg.useAssetProgram("demosaicp12f");
        glProg.setTexture("bayerTexture", glTexture);
        glProg.setTexture("greenTexture", WorkingTexture);
        outp = basePipeline.getMain();
        glProg.drawBlocks(outp);
        //glProg.drawBlocks(WorkingTexture);
        glProg.useAssetProgram("demosaicp2");
        glProg.setTexture("RawBuffer", glTexture);
        glProg.setTexture("GreenBuffer", outp);
        glProg.drawBlocks(WorkingTexture);
        glProg.close();
        endT("Demosaic2");
    }
}
