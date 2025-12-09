    package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

    import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
    import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
    import com.particlesdevs.photoncamera.processing.opengl.scripts.GLHistogram;
    import com.particlesdevs.photoncamera.settings.annotations.Tunable;
    import com.particlesdevs.photoncamera.util.Log;

    public class AutoExposure extends Node {
    @Tunable(title = "Enable", category = "Auto Exposure", defaultValue = 1, min = 0, max = 1, step = 1, description = "Enable post processing auto exposure adjustment")
    boolean enable;
    
    @Tunable(title = "Target Brightness", category = "Auto Exposure", max = 255.0f, defaultValue = 153.6f)
    float target = 153.6f;
    
    @Tunable(title = "Noise Max", category = "Auto Exposure", max = 1.0f, defaultValue = 0.1f)
    float noiseMax = 0.1f;
    
    @Tunable(title = "Gain Max", category = "Auto Exposure", max = 20.0f, defaultValue = 8.0f)
    float gainMax = 8.0f;
    
    public AutoExposure() {
        super("", "AutoExposure");
    }

    @Override
    public void AfterRun() {
    }

    @Override
    public void Compile() {}
    @Override
    public void Run() {
        if(!enable) {
            WorkingTexture = previousNode.WorkingTexture;
            return;
        }
        // Values are automatically injected in BeforeRun()!
        GLHistogram histogram = new GLHistogram(glProg, 256);
        histogram.Rc = true;
        histogram.Gc = true;
        histogram.Bc = true;
        int[][] result = histogram.Compute(previousNode.WorkingTexture);
        float sum = 0.0f;
        int cnt = 0;
        for (int i = 0; i < 256; i++) {
            sum += result[0][i] * i + result[1][i] * i + result[2][i] * i;
            cnt += result[0][i] + result[1][i] + result[2][i];
        }
        glProg.useAssetProgram("autoexposure/apply");
        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);

        float avg = sum / cnt;
        float mpy = target / avg;
        float gainNoiseMax = (float) (noiseMax / Math.sqrt(basePipeline.noiseS * 0.5 + basePipeline.noiseO));
        if (mpy > gainNoiseMax) {
            Log.d("AutoExposure", "Clamping gain by noise from " + mpy + " to " + gainNoiseMax);
            mpy = gainNoiseMax;
        }
        if(mpy > gainMax) {
            Log.d("AutoExposure", "Clamping gain by max from " + mpy + " to " + gainMax);
            mpy = gainMax;
        }
        Log.d("AutoExposure", "Average brightness: " + avg + ", multiplier: " + mpy);
        glProg.setVar("mpy",mpy);
        WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
    }
}
