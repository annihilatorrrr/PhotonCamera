package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.util.Log;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

public class BayerFilter extends Node {


    public BayerFilter() {
        super("", "BayerFilter");
    }

    @Override
    public void Compile() {}

    int tile = 8;
    @Override
    public void Run() {
        glProg.setLayout(tile,tile,1);
        glProg.setDefine("TILE",tile);
        glProg.setDefine("CONCAT", 1);
        glProg.useAssetProgram("concat",true);
        glProg.setTextureCompute("inTexture",previousNode.WorkingTexture,false);
        WorkingTexture = basePipeline.getMain();
        glProg.setTextureCompute("outTexture",WorkingTexture,true);
        glProg.computeManual(WorkingTexture.mSize.x/(tile*2),WorkingTexture.mSize.y/(tile*2),1);

        glProg.setLayout(tile,tile,1);
        glProg.setDefine("OUTSET",previousNode.WorkingTexture.mSize);
        glProg.setDefine("TILE",tile);
        glProg.setDefine("NOISEO",basePipeline.noiseO);
        glProg.setDefine("NOISES",basePipeline.noiseS);
        float ks = 1.0f + Math.min((basePipeline.noiseS+basePipeline.noiseO) * 3.0f * 100000.f, 34.f);
        int msize = 5 + (int)ks - (int)ks%2;
        Log.d("ESD3D", "KernelSize: "+ks+" MSIZE: "+msize);
        glProg.setDefine("KERNELSIZE", ks);
        glProg.setDefine("MSIZE", msize);
        glProg.useAssetProgram("esd3d2bayer",true);
        glProg.setTextureCompute("inTexture",WorkingTexture,false);
        WorkingTexture = basePipeline.getMain();
        glProg.setTextureCompute("outTexture",WorkingTexture,true);
        //for(int i =0; i<5;i++)
        glProg.computeManual(WorkingTexture.mSize.x/(tile*2),WorkingTexture.mSize.y/(tile*2),1);

        glProg.setLayout(tile,tile,1);
        glProg.setDefine("TILE",tile);
        glProg.setDefine("CONCAT", 0);
        glProg.useAssetProgram("concat",true);
        glProg.setTextureCompute("inTexture",WorkingTexture,false);
        WorkingTexture = basePipeline.getMain();
        glProg.setTextureCompute("outTexture",WorkingTexture,true);
        glProg.computeManual(WorkingTexture.mSize.x/(tile*2),WorkingTexture.mSize.y/(tile*2),1);

        glProg.closed = true;
    }
}
