package com.particlesdevs.photoncamera.processing.opengl.scripts;

import android.graphics.Point;
import android.util.Log;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.processor.UnlimitedProcessor;
import com.particlesdevs.photoncamera.processing.opengl.GLCoreBlockProcessing;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLOneScript;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;

import static android.opengl.GLES20.GL_MIRRORED_REPEAT;
import static android.opengl.GLES20.GL_NEAREST;
import static com.particlesdevs.photoncamera.processing.processor.UnlimitedProcessor.unlimitedCounter;

public class AverageRaw extends GLOneScript {
    GLTexture in1,in2,first,second,stack,stack2,finalTex;
    private GLProg glProg;
    int used = 1;
    int stackUsed = 1;

    private boolean stacked = false;
    public AverageRaw(Point size, String name) {
        super(size, new GLCoreBlockProcessing(size,new GLFormat(GLFormat.DataType.UNSIGNED_16)), "average", name);
    }
    float[] wpoints;
    public void Init(){
        stacked = false;
        first = new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_16));
        second = new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_16));
        stack = new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_16));
        stack2 = new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_16));
        finalTex = new GLTexture(size,new GLFormat(GLFormat.DataType.UNSIGNED_16));
        float []oldp = PhotonCamera.getParameters().whitePoint;
        wpoints = new float[oldp.length];
        float min = 1000.f;
        for(float p : oldp){
            if(p<min){
                min = p;
            }
        }
        System.arraycopy(oldp, 0, wpoints, 0, oldp.length);
    }
    GLTexture GetAlterIn(){
        if(used == 1) {
            return first;
        } else {
            return second;
        }
    }
    GLTexture GetAlterOut(){
        if(used == 1){
            used = 2;
            return second;
        } else {
            used = 1;
            return first;
        }
    }

    GLTexture GetStackedIn(){
        if(stackUsed == 1) {
            return stack;
        } else {
            return stack2;
        }
    }
    GLTexture GetStackedOut(){
        if(stackUsed == 1){
            stackUsed = 2;
            return stack2;
        } else {
            stackUsed = 1;
            return stack;
        }
    }
    private int cnt2 = 1;
    @Override
    public void Run() {
        //Stage 1 average alternate texture
        glProg = glOne.glProgram;
        if (in1 == null)
            Init();

        Compile();
        AverageParams scriptParams = (AverageParams) additionalParams;
        in1 = GetAlterIn();
        in2 = new GLTexture(size, new GLFormat(GLFormat.DataType.UNSIGNED_16), scriptParams.inp2, GL_NEAREST, GL_MIRRORED_REPEAT);
        if (in1 == null) {
            glProg.setVar("first", 1);
        } else {
            glProg.setVar("first", 0);
            glProg.setTexture("InputBuffer", in1);
        }
        glProg.setTexture("InputBuffer2", in2);
        glProg.setVar("CfaPattern",PhotonCamera.getParameters().cfaPattern);
        glProg.setVar("blacklevel", PhotonCamera.getParameters().blackLevel);
        glProg.setVar("WhitePoint", wpoints);
        glProg.setVar("whitelevel", (int) (PhotonCamera.getParameters().whiteLevel));
        glProg.setVar("unlimitedcount", unlimitedCounter);

        //WorkingTexture.BufferLoad();
        glProg.drawBlocks(GetAlterOut());
        //glOne.glProcessing.drawBlocksToOutput();
        AfterRun();
        //Stage 2 average stack
        if(unlimitedCounter > 60) {
            AverageStack();
        }
    }
    private void AverageStack(){
        glProg.useAssetProgram("averageff");
        GLTexture alIn = GetAlterIn();
        glProg.setTexture("InputBuffer",alIn);
        if (stacked) {
            glProg.setTexture("InputBuffer2", GetStackedIn());
        } else {
            glProg.setTexture("InputBuffer2", alIn);
        }
        glProg.setVar("unlimitedcount",Math.min(cnt2,250));
        glProg.drawBlocks(GetStackedOut());
        Log.d(Name,"AverageShift:"+Math.min(cnt2,250));
        stacked = true;
        cnt2++;
        unlimitedCounter = 1;
    }
    public void FinalScript(){
        //AverageStack();
        glProg = glOne.glProgram;
        glProg.useAssetProgram("medianfilterhotpixeltoraw");
        glProg.setVar("CfaPattern",PhotonCamera.getParameters().cfaPattern);
        Log.d(Name,"CFAPattern:"+PhotonCamera.getParameters().cfaPattern);
        if(stacked) {
            glProg.setTexture("InputBuffer", GetStackedIn());
        } else {
            glProg.setTexture("InputBuffer", GetAlterIn());
        }
        glProg.setVar("whitelevel",(int) UnlimitedProcessor.FAKE_WL);
        //in1 = WorkingTexture;
        finalTex.BufferLoad();
        glOne.glProcessing.drawBlocksToOutput();
        first.close();
        second.close();
        stack.close();
        stack2.close();
        finalTex.close();
        glProg.close();
        Output = glOne.glProcessing.mOutBuffer;
    }

    @Override
    public void AfterRun() {
        in2.close();
    }
}