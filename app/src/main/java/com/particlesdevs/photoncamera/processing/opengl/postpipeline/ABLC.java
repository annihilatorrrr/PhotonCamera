package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.annotation.SuppressLint;

import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.opengl.scripts.ABL;

public class ABLC extends Node {
    private static final String TAG = "ABLC";
    
    public ABLC() {
        super("", "ABLC");
    }

    @Override
    public void Compile() {
    }

    @SuppressLint("DefaultLocale")
    @Override
    public void Run() {
        ABL abl = new ABL(basePipeline.glint.glProcessing, 256);

        // Use bruteforce method to find optimal black levels that minimize color shifting
        //float[] blackLevels = bruteforceOptimalBlackLevels(hist);
        double noise = Math.sqrt(basePipeline.noiseS + basePipeline.noiseO);
        float[] blackLevels = abl.Compute(
                basePipeline.mParameters.whitePoint.clone(),
                noise,
                previousNode.WorkingTexture
        );

        Log.d(TAG, String.format("Bruteforce Black Levels - R: %.4f, G: %.4f, B: %.4f", 
               blackLevels[0], blackLevels[1], blackLevels[2]));

        // Apply black level correction
        glProg.useAssetProgram("levelcorrection");
        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
        glProg.setVar("blackLevel", blackLevels);
        WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
    }
}
