package com.particlesdevs.photoncamera.processing.opengl.scripts;

import android.content.Context;
import android.graphics.Point;
import android.util.Pair;

import com.particlesdevs.photoncamera.processing.ml.KernelNetNcnnProcessor;
import com.particlesdevs.photoncamera.processing.ml.KernelNetResult;
import com.particlesdevs.photoncamera.processing.opengl.GLBuffer;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;
import com.particlesdevs.photoncamera.util.Log;

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
import com.particlesdevs.photoncamera.settings.DynamicNoiseStore;
import com.particlesdevs.photoncamera.util.BufferUtils;
import com.particlesdevs.photoncamera.util.Math2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_MIRRORED_REPEAT;
import static android.opengl.GLES20.GL_NEAREST;
import static com.particlesdevs.photoncamera.processing.processor.ProcessorBase.FAKE_WL;

public class ESD4D extends GLOneScript {
    public Parameters parameters;
    ArrayList<ImageFrame> images;
    //ByteBuffer alignment;
    GLProg glProg;
    GLUtils glUtils;
    public ESD4D(Point size, ArrayList<ImageFrame> images) {
        super(size, new GLCoreBlockProcessing(size,new GLFormat(GLFormat.DataType.UNSIGNED_16), GLDrawParams.Allocate.Direct),"", "ESD4D", true);
        this.glProg = glOne.glProgram;
        this.images = images;
        //this.alignment = alignment;
    }

    /**
     * KernelNet runs natively through ncnn on the Vulkan backend (see
     * {@link KernelNetNcnnProcessor}).
     */

    @Override
    public void Compile(){}
    private int baseCnt = 0;

    private GLTexture getBase(){
        if(baseCnt == 0){
            baseCnt++;
            return baseAlter;
        } else {
            baseCnt = 0;
            return base;
        }
    }
    float noiseS;
    float noiseO;
    GLBuffer hotPixelBuffer;
    int hotPixelCount;
    @Tunable(title = "Max hotPixels", category = "Merge", description = "Statistical cpu filtering count threshold", min = 16384, max = 262144, step = 1000, defaultValue = 65535)
    int MAX_HOT_PIXELS;
    @Tunable(title = "Max reasonable hotPixels", category = "Merge", description = "Statistical cpu filtering count threshold", min = 1000, max = 10000, step = 100, defaultValue = 2000)
    int MAX_REASONABLE_HOTPIXELS;

    @Tunable(title = "Enable hotPixel correction", category = "Merge", min = 0, max = 1, step = 1, defaultValue = 0)
    boolean enableHotPixelCorrection;

    /**
     * Averages up to 10 frames (or fewer if not available) into a single rgba16f texture
     * at rawHalf resolution. Uses incremental mix: mix(current, new, 1/(i+1)) which yields
     * a proper running average without overflow.
     */
    private GLTexture buildAveragedFrame(float[] blackLevel, int tile) {
        Point rawHalf = new Point(parameters.rawSize.x / 2, parameters.rawSize.y / 2);
        int maxFrames = Math.min(10, images.size());

        GLTexture avgA     = new GLTexture(rawHalf, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        GLTexture avgB     = new GLTexture(rawHalf, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        GLTexture tempFloat = new GLTexture(rawHalf, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        GLTexture tempRaw  = maxFrames > 1
                ? new GLTexture(parameters.rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16, 1), null, GL_NEAREST, GL_CLAMP_TO_EDGE)
                : null;

        GLTexture avgCurrent = avgA;
        GLTexture avgNext    = avgB;

        for (int i = 0; i < maxFrames; i++) {
            GLTexture rawSrc = (i == 0) ? inputBase : tempRaw;
            if (i > 0) {
                tempRaw.loadData(images.get(i).buffer);
            }

            // Convert raw Bayer -> normalized rgba16f vec4 (one texel per 2x2 Bayer quad)
            glProg.setLayout(tile, tile, 1);
            glProg.useAssetProgram("merge/merge00", true);
            glProg.setVar("whiteLevel", (float) parameters.whiteLevel);
            glProg.setVar("blackLevel", blackLevel);
            glProg.setVar("exposure", 1.0f / images.get(0).pair.layerMpy);
            glProg.setVar("createDiff", 0);
            glProg.setVar("cfaPattern", parameters.cfaPattern);
            glProg.setTexture("inTexture", rawSrc);
            glProg.setTextureCompute("outTexture", tempFloat, true);
            glProg.computeAuto(rawHalf, 1);

            // Incremental mix: mix(currentAvg, newFrame, 1/(i+1))
            // i=0 → weight=1.0 copies newFrame wholesale (currentAvg is uninitialised zeros)
            float weight = 1.0f / (i + 1);
            glProg.setLayout(tile, tile, 1);
            glProg.useAssetProgram("merge/avermix", true);
            glProg.setTextureCompute("currentTexture", avgCurrent, false);
            glProg.setTextureCompute("newTexture",     tempFloat,  false);
            glProg.setTextureCompute("outTexture",     avgNext,    true);
            glProg.setVar("weight", weight);
            glProg.computeAuto(rawHalf, 1);

            // Ping-pong: avgNext becomes the new accumulator
            GLTexture swap = avgCurrent;
            avgCurrent = avgNext;
            avgNext    = swap;
        }

        avgNext.close();
        tempFloat.close();
        if (tempRaw != null) tempRaw.close();
        Log.d(Name, "Averaged " + maxFrames + " frame(s) for hot pixel detection");
        return avgCurrent; // caller must close
    }

    private GLBuffer detectHotPixels(GLTexture avgTex) {
        GLBuffer res = new GLBuffer(MAX_HOT_PIXELS*4+1, new GLFormat(GLFormat.DataType.UNSIGNED_32));
        glProg.setLayout(8,8,1);
        glProg.useAssetProgram("merge/hotpixeldetect", true);
        glProg.setVar("noiseS", noiseS);
        glProg.setVar("noiseO", noiseO);
        glProg.setVar("detectThr", (float) detectThr);
        glProg.setVar("maxCount", MAX_HOT_PIXELS);
        glProg.setTexture("inTexture", avgTex);
        glProg.setBufferCompute("HotPixelList",res);
        glProg.computeAuto(base.mSize, 1);
        int[] outputArr = res.readBufferIntegers(false);
        int rawCount = Math.min(outputArr[0], MAX_HOT_PIXELS);
        Log.d(Name, "Hot pixels detected (raw):" + rawCount);
        
        hotPixelCount = filterHotPixels(outputArr, rawCount, res);
        Log.d(Name, "Hot pixels after filtering:" + hotPixelCount);
        return res;
    }
    
    private int filterHotPixels(int[] data, int count, GLBuffer buffer) {
        if (count <= 0) return 0;
        
        // Structure: data[0] = count, then for each pixel: x, y, channels, strength
        ArrayList<int[]> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int idx = 1 + i * 4;
            int x = data[idx];
            int y = data[idx + 1];
            int ch = data[idx + 2];
            int strength = data[idx + 3];
            candidates.add(new int[]{x, y, ch, strength, i});
        }
        
        // If too many detections, likely false positives - filter by strength
        if (count > MAX_REASONABLE_HOTPIXELS) {
            Log.d(Name, "Too many hot pixels, filtering by strength");
            // Sort by strength (descending)
            candidates.sort((a, b) -> Integer.compare(b[3], a[3]));
            // Keep only the strongest
            while (candidates.size() > MAX_REASONABLE_HOTPIXELS) {
                candidates.remove(candidates.size() - 1);
            }
        }
        
        ArrayList<int[]> filtered = candidates;
        
        // Statistical outlier removal based on strength distribution
        if (filtered.size() > 50) {
            // Calculate mean and stddev of strength
            double sum = 0, sumSq = 0;
            for (int[] c : filtered) {
                sum += c[3];
                sumSq += (double)c[3] * c[3];
            }
            double mean = sum / filtered.size();
            double variance = sumSq / filtered.size() - mean * mean;
            double stddev = Math.sqrt(Math.max(variance, 1));
            
            // Remove weak outliers (strength < mean - 1.5*stddev)
            double threshold = mean - 1.5 * stddev;
            ArrayList<int[]> statistical = new ArrayList<>();
            for (int[] c : filtered) {
                if (c[3] >= threshold) {
                    statistical.add(c);
                }
            }
            Log.d(Name, "Statistical filtering: mean=" + (int)mean + " stddev=" + (int)stddev + " thr=" + (int)threshold);
            Log.d(Name, "Removed " + (filtered.size() - statistical.size()) + " weak detections");
            filtered = statistical;
        }
        
        // Repack filtered results back into buffer
        int finalCount = filtered.size();
        data[0] = finalCount;
        for (int i = 0; i < finalCount; i++) {
            int[] c = filtered.get(i);
            int idx = 1 + i * 4;
            data[idx] = c[0];
            data[idx + 1] = c[1];
            data[idx + 2] = c[2];
            data[idx + 3] = c[3];
        }
        buffer.uploadBuffer(data, finalCount * 4 + 1);
        
        return finalCount;
    }

    private void correctHotPixelsBase(GLBuffer buffer, int count){
        if (count > 0) {
            glProg.setLayout(64, 1, 1);
            glProg.useAssetProgram("merge/hotpixelcorrect", true);
            glProg.setBufferCompute("HotPixelList", buffer);
            glProg.setTextureCompute("inTexture", base, false);
            glProg.setTextureCompute("outTexture", base, true);
            glProg.computeManual((count + 63) / 64, 1, 1);
            Log.d(Name, "Hot pixels corrected in base:" + count);
        }
    }

    private void correctHotPixelsInAlter(GLBuffer buffer, int count){
        if (count > 0) {
            glProg.setLayout(64, 1, 1);
            glProg.useAssetProgram("merge/hotpixelcorrect", true);
            glProg.setBufferCompute("HotPixelList", buffer);
            glProg.setTextureCompute("inTexture", alter, false);
            glProg.setTextureCompute("outTexture", alter, true);
            glProg.computeManual((count + 63) / 64, 1, 1);
            Log.d(Name, "Hot pixels corrected in alter:" + count);
        }
    }

    private void hotPixels(){
        float[] blackLevel = parameters.blackLevel;
        GLTexture avgTex = buildAveragedFrame(blackLevel, 8);
        hotPixelBuffer = detectHotPixels(avgTex);
        avgTex.close();
        correctHotPixelsBase(hotPixelBuffer, hotPixelCount);
    }

    GLTexture inputBase;
    GLTexture baseDiff;
    GLTexture base;
    GLTexture baseAlter;
    //GLTexture;
    GLTexture brightMap;
    /** CPU copy of brightMap (float32 grayscale luma in [0,1]) set by {@link #exportBrightMap()}. */
    public FloatBuffer brightMapCPU;
    /** Unpacked size of {@link #brightMapCPU} (row-major, width*height floats). */
    public Point brightMapCPUSize;
    /** KernelNet half-res parameter texture (s1, s2, rho in RGBA16F) for the anisotropic filter. */
    public GLTexture kernelsMap;
    /** Noise sigma fed to KernelNet (captured pre-merge-inflation). */
    float kernelSigma;
    GLTexture result;
    GLTexture inputAlter;
    GLTexture alter;
    GLTexture alignmentTex;
    /** Dense optical-flow alignment (FlowNet); non-null when useNcnnFlow ran. */
    FlowNetAlignment flowNetAlignment;
    @Tunable(title = "HotPixels detect threshold", category = "Merge", description = "Higher multiplier detects less hotpixels", min = 0.5f, max = 5.0f, step = 0.1f, defaultValue = 1.5f)
    double detectThr;

    @Tunable(title = "Enable Adaptive Noise Model", category = "Merge", description = "Creates noise multiplier based on stdev", min = 0, max = 1, step = 1, defaultValue = 1)
    boolean enableAdaptiveNoise;

    @Tunable(title = "Enable Alignment", category = "Merge", description = "Disable to test merging motion filtering without alignment", min = 0, max = 1, step = 1, defaultValue = 1)
    boolean enableAlignment;

    @Tunable(title = "FlowNet optical flow alignment", category = "Merge", description = "Align burst frames with the FlowNet dense optical flow model (ncnn) instead of the block pyramid", min = 0, max = 1, step = 1, defaultValue = 0)
    boolean useNcnnFlow;

    @Tunable(title = "Enable Adaptive Noise Storage", category = "Merge", description = "Persist fitted noise model into the dynamic multisample store", min = 0, max = 1, step = 1, defaultValue = 1)
    boolean enableNoiseStore;

    @Tunable(title = "Network merge noise multiplier", category = "Merge", description = "Scales the noise model fed to the kernel network", min = 0.1f, max = 20.0f, step = 0.05f, defaultValue = 1.0f)
    float noiseMpy;

    @Override
    public void Run() {
        com.particlesdevs.photoncamera.settings.TunableInjector.inject(this);
        Log.d("ESD4D", "Noise multiplier: " + noiseMpy);
        glUtils = new GLUtils(glOne.glProcessing);

        float minExp = 1.f;
        int minExpIdx = 0;
        int lowCnt = 0;
        for (int i = 1; i < images.size(); i++) {
            ImageFrame frame = images.get(i);
            float exposure = 1.f/frame.pair.layerMpy;
            Log.d("ESD4D", "exposure: " + exposure);
            if(exposure < 0.95f) {
                lowCnt++;
            }
            if(exposure < minExp) {
                minExpIdx = i;
                minExp = exposure;
            }
        }

        Point alignmentOutputSize = new Point(parameters.alignmentSize.x * parameters.tilesX,
                parameters.alignmentSize.y * ((images.size()-1)/parameters.tilesX + 1));
        Log.d("Alignment", "alignment pipeline size: " + alignmentOutputSize.x + " " + alignmentOutputSize.y);
        useNcnnFlow = enableAlignment && useNcnnFlow;
        if (enableAlignment && useNcnnFlow) {
            FlowNetAlignment flowNetAlignmentTmp = new FlowNetAlignment(alignmentOutputSize, images, glProg, glUtils, this, minExpIdx);
            flowNetAlignmentTmp.parameters = parameters;
            long startTime = System.currentTimeMillis();
            useNcnnFlow = flowNetAlignmentTmp.initFlow();
            Log.d("ESD4D", "FlowNet alignment init time: " + (System.currentTimeMillis() - startTime) + "ms");
            if (useNcnnFlow) {
                flowNetAlignment = flowNetAlignmentTmp;
                alignmentTex = flowNetAlignment.flowTex;
            } else {
                flowNetAlignmentTmp.close();
            }
        }
        if (enableAlignment && !useNcnnFlow) {
            PyramidAlignment pyramidAlignment = new PyramidAlignment(alignmentOutputSize, images, glProg, glUtils, this);
            pyramidAlignment.parameters = parameters;
            long startTime = System.currentTimeMillis();
            pyramidAlignment.Run();
            Log.d("ESD4D", "Alignment time: " + (System.currentTimeMillis() - startTime) + "ms");
            alignmentTex = pyramidAlignment.Result;
            pyramidAlignment.close();
        } else if (!enableAlignment) {
            alignmentTex = new GLTexture(alignmentOutputSize, new GLFormat(GLFormat.DataType.FLOAT_16, 4),
                    BufferUtils.getFrom(new float[alignmentOutputSize.x * alignmentOutputSize.y * 4]),
                    GL_NEAREST, GL_CLAMP_TO_EDGE);
            Log.d("ESD4D", "Alignment disabled, using identity alignment");
        }
        Point raw = parameters.rawSize;
        Point rawHalf = new Point(parameters.rawSize.x/2,parameters.rawSize.y/2);
        result = new GLTexture(raw,new GLFormat(GLFormat.DataType.UNSIGNED_16,1), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        inputBase = new GLTexture(parameters.rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16,1),images.get(0).buffer, GL_NEAREST, GL_CLAMP_TO_EDGE);
        // Pyramid diff
        baseDiff = new GLTexture(rawHalf,new GLFormat(GLFormat.DataType.FLOAT_16,4),null,GL_LINEAR,GL_CLAMP_TO_EDGE);
        // Temporal result
        base = new GLTexture(rawHalf,new GLFormat(GLFormat.DataType.FLOAT_16,4),null,GL_LINEAR,GL_CLAMP_TO_EDGE);
        baseAlter = new GLTexture(rawHalf,new GLFormat(GLFormat.DataType.FLOAT_16,4),null,GL_LINEAR,GL_CLAMP_TO_EDGE);
        alter = new GLTexture(rawHalf,new GLFormat(GLFormat.DataType.FLOAT_16,4),null,GL_LINEAR,GL_CLAMP_TO_EDGE);
        // Pack 4 horizontal luma samples per rgba16f texel (r16f image formats are
        // rejected by some drivers) -> texture is 4x smaller in x.
        Point brightMapSize = new Point((rawHalf.x + 3) / 4, rawHalf.y);
        brightMap = new GLTexture(brightMapSize,new GLFormat(GLFormat.DataType.FLOAT_16,4));
        brightMapCPUSize = new Point(brightMapSize.x * 4, brightMapSize.y);
        float[] blackLevel = parameters.blackLevel;
        //float[] blackLevel = new float[]{parameters.blackLevel[0]*0.5f, parameters.blackLevel[1]*0.5f, parameters.blackLevel[2]*0.5f, parameters.blackLevel[3]*0.5f};
        //float bl = Math.max(Math.max(parameters.blackLevel[0], parameters.blackLevel[1]), Math.max(parameters.blackLevel[2], parameters.blackLevel[3]));
        glOne.glProgram.setDefine("RAWSIZE",parameters.rawSize);
        glOne.glProgram.setDefine("CFAPATTERN",(int)parameters.cfaPattern);

        float[] analogBalance = new float[4];
        switch (parameters.cfaPattern){
            case 0: // RGGB
                analogBalance[0] = 1.0f/parameters.whitePoint[0];
                analogBalance[1] = 1.0f/parameters.whitePoint[1];
                analogBalance[2] = 1.0f/parameters.whitePoint[1];
                analogBalance[3] = 1.0f/parameters.whitePoint[2];
                break;
            case 1: // GRBG
                analogBalance[0] = 1.0f/parameters.whitePoint[1];
                analogBalance[1] = 1.0f/parameters.whitePoint[0];
                analogBalance[2] = 1.0f/parameters.whitePoint[2];
                analogBalance[3] = 1.0f/parameters.whitePoint[1];
                break;
            case 2: // GBRG
                analogBalance[0] = 1.0f/parameters.whitePoint[1];
                analogBalance[1] = 1.0f/parameters.whitePoint[2];
                analogBalance[2] = 1.0f/parameters.whitePoint[0];
                analogBalance[3] = 1.0f/parameters.whitePoint[1];
                break;
            case 3: // BGGR
                analogBalance[0] = 1.0f/parameters.whitePoint[2];
                analogBalance[1] = 1.0f/parameters.whitePoint[1];
                analogBalance[2] = 1.0f/parameters.whitePoint[1];
                analogBalance[3] = 1.0f/parameters.whitePoint[0];
                break;
        }
        NoiseModeler modeler = parameters.noiseModeler;
        noiseS = modeler.baseModel[0].first.floatValue() +
                modeler.baseModel[1].first.floatValue() +
                modeler.baseModel[2].first.floatValue();
        noiseO = modeler.baseModel[0].second.floatValue() +
                modeler.baseModel[1].second.floatValue() +
                modeler.baseModel[2].second.floatValue();
        noiseS /= 3.f;
        noiseO /= 3.f;
        //GLUtils glUtils = new GLUtils(glOne.glProcessing);
        int tile = 8;
        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("merge/merge00",true);
        glProg.setVar("whiteLevel",(float)(parameters.whiteLevel));
        glProg.setVar("blackLevel", blackLevel);
        glProg.setVar("exposure", 1.f/images.get(0).pair.layerMpy);
        glProg.setVar("createDiff", 0);
        glProg.setVar("cfaPattern", parameters.cfaPattern);
        glProg.setVar("analogBalance", analogBalance);
        glProg.setVar("randF", (float)Math.random(), (float)Math.random());
        // Test value if enabled in shader
        //glProg.setVar("noiseS", 0.0013796629f);
        //glProg.setVar("noiseO", 8.3751265E-6f);
        //glProg.setVar("noiseS", 0.05f);
        //glProg.setVar("noiseO", 0.0f);
        glProg.setTexture("inTexture",inputBase);
        glProg.setTextureCompute("outTexture",base, true);
        glProg.computeAuto(new Point(base.mSize.x, base.mSize.y), 1);
        //glUtils.convertVec4(base, "vec4(0.5)", base);
        //var buff = glUtils.GenerateGLImage(base.mSize, 4);
        //Log.d(Name, "Buffer first:" + buff.byteBuffer.get(0) + " " + buff.byteBuffer.get(1));
        //glUtils.Result(base.mSize, "noiseInput", buff.byteBuffer);

        double adaptiveNMpy = 1.0;
        if (enableAdaptiveNoise) {
            // 2D histogram: (brightness_bin * NUM_VARIANCE_BINS + variance_bin) -> count
            // Model: variance = NoiseS * brightness + NoiseO  =>  sigma = sqrt(NoiseS*b + NoiseO)
            final int numBrightnessBins = 64;
            final int numVarianceBins = 64;
            final int noiseScanBins = numBrightnessBins * numVarianceBins; // 1024
            // Variance scale: max variance ~(numVarianceBins-0.5)/scale. Use 160 so we cover up to ~0.2 for noisy sensors.
            final float varianceScale = 64.0f * 6.0f;
            final float brightnessScale = 64.0f * (float)Math.sqrt(3.0f);
            GLHistogram noiseHist = new GLHistogram(glProg, noiseScanBins);
            noiseHist.Custom = true;
            noiseHist.Rc = true;
            noiseHist.Gc = false;
            noiseHist.Bc = false;
            noiseHist.Ac = false;
            noiseHist.exposure[0] = 1.0f;
            noiseHist.exposure[1] = 1.0f;
            noiseHist.exposure[2] = 1.0f;
            noiseHist.exposure[3] = 1.0f;
            noiseHist.CustomShader = "merge/noisehist";
            noiseHist.input1 = brightnessScale;
            noiseHist.input2 = varianceScale;
            int[][] noiseRes = noiseHist.Compute(base);
            int[] hist = noiseRes[0];
            int varCnt = 0;
            float[] weights = new float[numVarianceBins];
            float wSum = 0.0f;
            float minBr = 0.0f;
            for (int i = 0; i < noiseScanBins; i++) {
                int count = hist[i];
                var bin = i / numVarianceBins;
                var vin = i % numVarianceBins;
                if(vin == 0) {
                    varCnt = 0;
                }
                if (count <= 0 || bin == numBrightnessBins-1 || (varCnt >= 30 && vin == 63) || varCnt > 45) continue;
                varCnt++;
                if(minBr == 0.0f) {
                    minBr = ((float)bin + 0.5f) / brightnessScale;
                    minBr = (float) Math.pow(minBr, 2.0);
                }
                double w = count;
                weights[vin] += (float) w;
                wSum += w;
            }
            float wWindow = Math.max(weights[0], weights[1]);
            for (int i = 0; i < numVarianceBins; i++) {
                wWindow = Math2.mix(Math.max(wWindow, weights[i]), weights[i], 0.025f);
                weights[i] = 0.5f + wWindow / wSum;
                Log.d("DynamicNoise", "Variance weight: " + weights[i]);
            }
            // Weighted linear regression: variance = NoiseS * brightness + NoiseO
            double sumW = 0, sumWb = 0, sumWv = 0, sumWb2 = 0, sumWbv = 0;
            int points = 0;
            varCnt = 0;
            for (int i = 0; i < noiseScanBins; i++) {
                int count = hist[i];
                var bin = i / numVarianceBins;
                var vin = i % numVarianceBins;
                if(vin == 0) {
                    varCnt = 0;
                }
                if (count <= 0 || bin == numBrightnessBins-1 || (varCnt >= 30 && vin == 63) || varCnt > 45) continue;
                varCnt++;
                double brightness = ((double)(bin) + 0.5) / ((double)brightnessScale);
                brightness = Math.pow(brightness, 2.0);
                brightness = (brightness - minBr)/(1.0 - minBr);
                double variance = (vin + 0.5) / varianceScale;
                // Median-of-squared-deviations (shader "var") ≈ 0.6745*sigma, so a
                // single 1.4826 (=1/0.6745) converts it to sigma, then squaring gives
                // the true variance. (Double-multiplying previously biased S/O by ~2.2x.)
                //variance *= 1.4826;
                variance = Math.pow(variance, 2.0);

                Log.d("DynamicNoise", "vin:"+ vin + " bin: " + bin + " Variance raw: " + variance + " brightness: " + brightness + " count: " + count);
                double w = count * 1.0f;
                sumW += w;
                sumWb += w * brightness;
                sumWv += w * variance;
                sumWb2 += w * brightness * brightness;
                sumWbv += w * brightness * variance;
                points++;
            }
            //points = 9;
            if (points >= 1) {
                double denom = sumW * sumWb2 - sumWb * sumWb;
                if (denom > 1e-20) {
                    double fitS = (sumW * sumWbv - sumWb * sumWv) / denom;
                    double fitO = (sumWv - fitS * sumWb) / sumW;
                    fitS = Math.max(fitS, 1e-10);
                    Log.d("DynamicNoise",  "Fit S:" + fitS + " O:" + fitO);
                    // Keep at least 5% of original read noise so we don't collapse to zero on noisy sensors
                    double minO = 0.05 * noiseO;
                    fitO = Math.max(fitO, minO);
                    // Read-noise floor: O=S/7 overstates read noise now that the variance
                    // estimator is unbiased (previously S carried a ~2.2x bias that made
                    // S/7 a sane proxy). S/20 keeps a guard against O collapsing while no
                    // longer dominating realistic sensors (O/S is typically < 0.05).
                    fitO = Math.max(fitO, fitS/20);
                    fitS = Math.max(fitS, parameters.noiseModeler.SPlace(parameters.iso));
                    fitO = Math.max(fitO, parameters.noiseModeler.OPlace(parameters.iso)*3.0f);
                    // Commit the fitted S/O to the multisample noise map, then read
                    // back the blended (moving-average) value. Committing before
                    // reading makes the current estimation participate in the
                    // average, while the store's measurement-list guard skips
                    // duplicate scenes (same exposure/iso) to avoid bias. Using the
                    // blended output smooths per-capture estimator fluctuations.
                    double commitS = fitS;
                    double commitO = fitO;
                    DynamicNoiseStore.NoiseEstimate blended = null;
                    if (enableNoiseStore) {
                        blended = DynamicNoiseStore.dynamicNoiseStore.commitAndGet(
                                parameters.physicalID, parameters.iso,
                                parameters.noiseModeler.AnalogueISO,
                                commitS, commitO, parameters.exposureTime);
                    }
                    if (blended != null) {
                        fitS = blended.s;
                        fitO = blended.o;
                        // Re-apply floors defensively on the blended result.
                        //fitS = Math.max(fitS, parameters.noiseModeler.SPlace(parameters.iso));
                        //fitO = Math.max(fitO, parameters.noiseModeler.OPlace(parameters.iso));
                        Log.d("DynamicNoise", "Blended noise model from store: S=" + fitS
                                + " O=" + fitO + " for iso=" + parameters.iso);
                    }
                    fitO += fitS*fitS * 3.0/8.0; // Correction factor
                    noiseS = (float) fitS;
                    noiseO = (float) fitO;
                    Log.d("DynamicNoise",  "Fitted noise model: NoiseS=" + noiseS + " NoiseO=" + noiseO + " Half=" + Math.sqrt(noiseS * 0.5 + noiseO) + " (points=" + points + ")");
                    parameters.noiseModeler.baseModel = new Pair[] {
                            new Pair<>((double) noiseS, (double) noiseO),
                            new Pair<>((double) noiseS, (double) noiseO),
                            new Pair<>((double) noiseS, (double) noiseO)};
                }
                adaptiveNMpy = 1.0;
            } else {
                // Fallback: scale original model to match observed at mid-gray (same as before)
                double modelSigmaMid = Math.sqrt(noiseS * 0.5 + noiseO);
                if (modelSigmaMid > 1e-10) {
                    double sumWeightedSigma = 0, sumWeightedCount = 0;
                    for (int i = 0; i < noiseScanBins; i++) {
                        int count = hist[i];
                        if (count <= 0) continue;
                        double sigma = ((i % numVarianceBins + 0.5) / varianceScale) * 1.4826;
                        sumWeightedSigma += sigma * count;
                        sumWeightedCount += count;
                    }
                    if (sumWeightedCount > 0) {
                        double observedSigma = sumWeightedSigma / sumWeightedCount;
                        adaptiveNMpy = observedSigma / modelSigmaMid;
                        adaptiveNMpy = Math2.clamp(adaptiveNMpy, 1.0, 4.0);
                    }
                }
                Log.d("DynamicNoise", "Adaptive Mpy (fallback): " + adaptiveNMpy + " (insufficient points=" + points + ")");
            }
        }
        parameters.noiseModeler.setAdaptiveMpy(adaptiveNMpy);
        double noisempy = Math.pow(2.0, PhotonCamera.getSettings().mergeStrength);
        //double noiseMin = 1.0/(double)parameters.whiteLevel;
        double noiseMin = 1e-6;
        kernelSigma = (float) Math.sqrt(noiseS * 0.5 + noiseO);
        noiseS = (float)Math.max(noiseS * noisempy * adaptiveNMpy * adaptiveNMpy,noiseMin);
        noiseO = (float)Math.max(noiseO * noisempy * adaptiveNMpy * adaptiveNMpy,noiseMin);
        if(enableHotPixelCorrection)
            hotPixels();

        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("merge/mergeGrayscale",true);
        glProg.setVar("inSize", rawHalf);
        glProg.setTextureCompute("inTexture",base, false);
        glProg.setTextureCompute("outTexture",brightMap, true);
        glProg.computeAuto(brightMap.mSize, 1);
        exportBrightMap();
        KernelNetResult kernelParams = runKernelNetInference(kernelSigma * noiseMpy);
        kernelsMap = createKernelsMap(kernelParams);

        //Point aSize = new Point(parameters.rawSize.x/(2*parameters.tile) + 1, parameters.rawSize.y/(2*parameters.tile) + 1);
        Point border = new Point(16,16);
        inputAlter = new GLTexture(parameters.rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16, 1), null, GL_NEAREST, GL_MIRRORED_REPEAT);
        //alignmentTex = new GLTexture(aSize, new GLFormat(GLFormat.DataType.FLOAT_32, 2), alignment, GL_NEAREST, GL_MIRRORED_REPEAT);

        //counter.put(1.0f,1.0f);
        float cnt1 = 2.0f;

        float cnt2 = 1.0f;
        //Log.d("ESD4D", "alignment size: " + aSize.x + " " + aSize.y);
        Log.d("ESD4D", "alignment size: " + parameters.alignmentSize.x + " " + parameters.alignmentSize.y);
        float maxBlack = Math.max(blackLevel[0], Math.max(blackLevel[1], Math.max(blackLevel[2], blackLevel[3])));
        float minLevel = (float) (1.0/(double)(parameters.whiteLevel-maxBlack));

        for (int f = 0; f < images.size(); f++) {
            if(f == minExpIdx) continue;
            int ind = f;
            if(ind == 0){
                ind = minExpIdx;
            }
            ImageFrame frame = images.get(ind);
            float exposure = 1.f/frame.pair.layerMpy;
            Point shift = PyramidAlignment.alignmentShift(parameters, ind);
            //int f = 1;
            Log.d("ESD4D", "load:"+frame.pair.curlayer.name() + " " + frame.pair.layerMpy);
            inputAlter.loadData(frame.buffer);

            GLTexture flowTex = null;
            if(useNcnnFlow) {
                // Dense FlowNet optical flow for THIS alter frame, computed just
                // in time (one pair at a time, no stored flow fields). Must run
                // before the mergeAlign program is bound below.
                flowTex = flowNetAlignment.computeFlow(ind);
            }

            // Convert inputAlter to alter (vec4 format)
            glProg.setLayout(tile, tile, 1);
            glProg.useAssetProgram("merge/merge00", true);
            glProg.setVar("whiteLevel", (float)(parameters.whiteLevel));
            glProg.setVar("blackLevel", blackLevel);
            glProg.setVar("exposure", 1.f/images.get(0).pair.layerMpy);
            glProg.setVar("createDiff", 0);
            glProg.setVar("cfaPattern", parameters.cfaPattern);
            glProg.setTexture("inTexture", inputAlter);
            glProg.setTextureCompute("outTexture", alter, true);
            glProg.computeAuto(new Point(alter.mSize.x, alter.mSize.y), 1);
            
            correctHotPixelsInAlter(hotPixelBuffer, hotPixelCount);
            //alignmentTex.loadData(alignment.position((ind-1)*(aSize.x*aSize.y*4*2)));
            glProg.setDefine("TILE_AL", parameters.tile);
            glProg.setLayout(tile, tile, 1);
            glProg.useAssetProgram(useNcnnFlow ? "merge/mergeAlignFlow" : "merge/mergeAlign", true);
            glProg.setVar("rawHalf", rawHalf);
            glProg.setVar("whiteLevel", (float) (parameters.whiteLevel));
            glProg.setVar("whitePoint", parameters.whitePoint);
            glProg.setVar("blackLevel", blackLevel);
            glProg.setVar("minLevel",minLevel);
            glProg.setVar("exposure", exposure);
            glProg.setVar("analogBalance", analogBalance);
            if(exposure >= 0.95f) {
                if(lowCnt > 1)
                    glProg.setVar("exposureLow", minExp - 0.05f);
                else {
                    glProg.setVar("exposureLow", 0.0f);
                }
            } else {
                glProg.setVar("exposureLow", 0.0f);
            }
            glProg.setVar("createDiff", 1);
            glProg.setVar("noiseS", noiseS);
            glProg.setVar("noiseO", noiseO);
            glProg.setVar("border", border);
            if(useNcnnFlow) {
                glProg.setTexture("alignmentTexture", flowTex);
            } else {
                glProg.setVar("shift", shift);
                glProg.setVar("alignmentSize", parameters.alignmentSize);
                glProg.setTexture("alignmentTexture", alignmentTex);
            }
            glProg.setTexture("inTexture", inputBase);
            glProg.setTextureCompute("baseTexture",base, false);
            glProg.setTextureCompute("alterTexture", alter, false);
            glProg.setTextureCompute("outTexture", baseDiff, true);
            glProg.computeAuto(baseDiff.mSize, 1);

            Log.d("ESD4D", "create diff");


            glProg.setLayout(tile, tile, 1);
            glProg.useAssetProgram("merge/mergeCombineWeight", true);
            glProg.setVar("cfaPattern", parameters.cfaPattern);
            glProg.setTexture("inTex", inputBase);
            glProg.setTexture("kernelsMap", kernelsMap);
            glProg.setTextureCompute("inTexture", base, false);
            glProg.setTextureCompute("diffTexture", baseDiff, false);
            base = getBase();
            glProg.setTextureCompute("outTexture", base, true);
            glProg.setVar("noiseS", noiseS);
            glProg.setVar("noiseO", noiseO);
            glProg.setVar("whiteLevel", (float) (parameters.whiteLevel));
            glProg.setVar("blackLevel", blackLevel);
            glProg.setVar("analogBalance", analogBalance);
            glProg.setVar("exposure", exposure);
            if(exposure >= 0.95f){
                glProg.setVar("weight", 1.0f/cnt1);
                //glProg.setVar("exposure", minExp);
                cnt1+=1.0f;
            } else {
                glProg.setVar("weight", 1.0f/cnt2);
                //glProg.setVar("exposure", 1.0f);
                cnt2+=1.0f;
            }
            //glProg.setVar("exposure", exposure);
            //glProg.setVar("weight",  1.0f);
            glProg.computeAuto(base.mSize, 1);
        }

        float[] bl2 = new float[4];
        for (int i = 0; i < 4; i++) {
            bl2[i] = blackLevel[i]*(FAKE_WL / parameters.whiteLevel);
        }
        glProg.setDefine("WHITE_LEVEL", FAKE_WL);
        glProg.setDefine("BLACK_LEVEL", bl2);
        glProg.setLayout(tile,tile,1);
        glProg.useAssetProgram("merge/merge2o");
        glProg.setTexture("inTexture",base);
        glProg.setTexture("alignmentTexture", alignmentTex);
        result.BufferLoad();
        glOne.glProcessing.drawBlocksToOutput();
        Output = glOne.glProcessing.mOutBuffer;
        AfterRun();
    }

    /**
     * Reads brightMap back to CPU. The packed rgba16f texels decode directly to
     * row-major grayscale luma (4 x-samples per texel), so reading the RGBA floats
     * in order already yields the full-width buffer. Must be called while the GL
     * context is current and before AfterRun() closes brightMap.
     */
    public FloatBuffer exportBrightMap() {
        if (brightMap == null) return null;
        brightMap.BufferLoad();
        ByteBuffer raw = brightMap.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_32, 4), true);
        raw.order(ByteOrder.nativeOrder());
        brightMapCPU = raw.asFloatBuffer();
        return brightMapCPU;
    }

    /**
     * Runs the KernelNet parameter model on the previously exported {@link #brightMapCPU}
     * (call {@link #exportBrightMap()} first). Returns half-resolution kernel params
     * (s1, s2, rho) as channel-major floats, or null if the model isn't available.
     * NOTE: blocks the GL thread for the inference duration (~40-170ms at high res).
     */
    public KernelNetResult runKernelNetInference(float sigma) {
        if (brightMapCPU == null || brightMapCPUSize == null) return null;
        Context ctx = PhotonCamera.getAppContext();
        if (ctx == null) return null;
        KernelNetNcnnProcessor processor = new KernelNetNcnnProcessor(ctx);
        try {
            if (!processor.isReady()) return null;
            return processor.runInference(brightMapCPU, brightMapCPUSize.x, brightMapCPUSize.y, sigma);
        } finally {
            processor.close();
        }
    }

    /**
     * Converts a KernelNet parameter map (channel-major s1, s2, rho floats at half-res)
     * into an RGBA16F texture for the anisotropic Gaussian filter: texel = (s1, s2, rho, 1).
     * The texture is left open for downstream use; caller owns it.
     */
    public GLTexture createKernelsMap(KernelNetResult result) {
        if (result == null) return null;
        int w = result.width();
        int h = result.height();
        int plane = w * h;
        FloatBuffer params = result.asFloatBuffer();
        float[] rgba = new float[plane * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int o = i * 4;
                rgba[o] = params.get(i);                 // s1
                rgba[o + 1] = params.get(plane + i);     // s2
                rgba[o + 2] = params.get(2 * plane + i); // rho
                rgba[o + 3] = 1.0f;
            }
        }
        GLTexture map = new GLTexture(new Point(w, h), new GLFormat(GLFormat.DataType.FLOAT_16, 4), null);
        map.loadData(FloatBuffer.wrap(rgba));
        return map;
    }

    @Override
    public void AfterRun() {
        if(hotPixelBuffer != null) hotPixelBuffer.close();
        inputAlter.close();
        alter.close();
        inputBase.close();
        baseDiff.close();
        base.close();
        baseAlter.close();
        brightMap.close();
        result.close();
        if(useNcnnFlow && flowNetAlignment != null) {
            // Closes flowTex (== alignmentTex), so drop the reference to avoid
            // a double close below.
            flowNetAlignment.close();
            flowNetAlignment = null;
            alignmentTex = null;
        } else {
            alignmentTex.close();
        }
        GLTexture.notClosed();
    }
}
