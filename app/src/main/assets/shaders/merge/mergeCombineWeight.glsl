#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
//uniform highp usampler2D alterTexture;
uniform highp usampler2D inTex;
uniform highp sampler2D kernelsMap;
layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 1) uniform highp readonly image2D diffTexture;
layout(rgba16f, binding = 2) uniform highp writeonly image2D outTexture;
#define TILE 2
#define CONCAT 1
uniform float weight;
uniform float weight2;
uniform float exposure;
uniform float noiseS;
uniform float noiseO;
uniform float whiteLevel;
uniform vec4 blackLevel;
uniform vec4 analogBalance;
uniform int cfaPattern;
// Optical flow refinement: per-pixel correction of the coarse alignment.
// The diff texture packs whole 2x2 Bayer quads per texel, so fractional
// resampling (bilinear) is illegal here - it would blend different color
// channels across quads. The refinement therefore only selects whole
// texel-block offsets via imageLoad.
uniform int enableFlow;
uniform float flowMaxDisp;
// Pre-inflation noise model (noiseS/noiseO are merge-strength inflated and
// would scale the significance gate with user settings).
uniform float flowNoiseS;
uniform float flowNoiseO;

uint getBayer(ivec2 coords, highp usampler2D tex){
    return texelFetch(tex,coords,0).r;
}

vec4 getBayerVec(ivec2 coords, highp usampler2D tex){
    vec4 c0 = vec4(getBayer(coords,tex),getBayer(coords+ivec2(1,0),tex),getBayer(coords+ivec2(0,1),tex),getBayer(coords+ivec2(1,1),tex));
    return clamp((c0 - blackLevel)/(vec4(whiteLevel)-blackLevel), 0.0, 1.0);
}

vec4 robustWeight(vec4 w){
    return vec4(min(w.r, min(w.g, min(w.b, w.a))));
}

// Group-shared tiles: 8x8 output texels plus the aprons the windows need.
// shTile (base) apron 5 covers the 11x11 combine kernel and the LK 5x5
// window with its gradient border. shDiff apron is 5 + FLOW_DISP_MAX: the
// combine window shifted by the accepted flow block, and the LK window
// shifted by the (unclamped) iteration blocks, which stay inside because a
// step length is capped at 1 texel per iteration. Filling cooperatively
// (~14 texels per thread across both tiles) turns every later window read
// of both images into shared-memory reads; only the two fills load images.
// 19*19 + 22*22 vec4 = 13520 bytes, under the 16KB ES 3.1 shared minimum.
#define GROUP 8                 // must match setLayout(8, 8, 1) in ESD4D
#define APRON 5                 // combine kernel radius
#define SH_STRIDE (GROUP + 2 * APRON + 1) // 19, odd stride avoids bank aliasing
#define SH_SIZE (SH_STRIDE * SH_STRIDE)
#define FLOW_DISP_MAX 2         // highest flowMaxDisp the shDiff halo admits
#define DAPRON (APRON + FLOW_DISP_MAX)
#define DSH_STRIDE (GROUP + 2 * DAPRON)
#define DSH_SIZE (DSH_STRIDE * DSH_STRIDE)
shared vec4 shTile[SH_SIZE];
shared vec4 shDiff[DSH_SIZE];

// Optical flow refinement window / iteration count / gates.
#define FLOW_R 2          // 5x5 estimation window
#define FLOW_ITERS 2      // discrete Gauss-Newton iterations
#define FLOW_DET_EPS 1e-8 // numerical floor: reject only truly textureless windows
#define FLOW_ACCEPT 0.95  // selected block's SAD must drop below this fraction of the zero-offset SAD

// Per-pixel Lucas-Kanade refinement of the coarse (tile / FlowNet) alignment.
// Template = base (accumulated reference), image = diff (coarsely warped alter).
// The solved flow is rounded to a whole texel-block offset (packed Bayer quads
// cannot be sampled fractionally) and that block is kept only when direct
// block matching beats the zero-offset candidate, so occlusions, flat areas
// and noise fall back to the coarse warp instead of drifting.
// The template gradients (and with them the structure tensor and its inverse)
// are iteration-invariant; both windows are served from the group's shared
// tiles, so the whole solve is shared-memory traffic only.
// lc / dc = this texel's coordinates inside shTile / shDiff.
ivec2 refineFlow(ivec2 xy, ivec2 lc, ivec2 dc) {
    const float n = float((2 * FLOW_R + 1) * (2 * FLOW_R + 1));
    float h00 = 0.0, h01 = 0.0, h11 = 0.0;
    for (int j = -FLOW_R; j <= FLOW_R; j++) {
        for (int i = -FLOW_R; i <= FLOW_R; i++) {
            int c = (lc.y + j) * SH_STRIDE + lc.x + i;
            vec4 gx = (shTile[c + 1] - shTile[c - 1]) * 0.5;
            vec4 gy = (shTile[c + SH_STRIDE] - shTile[c - SH_STRIDE]) * 0.5;
            h00 += dot(gx, gx);
            h01 += dot(gx, gy);
            h11 += dot(gy, gy);
        }
    }
    float det = h00 * h11 - h01 * h01;
    if (det < FLOW_DET_EPS * n * n) return ivec2(0); // nothing to solve on
    mat2 hinv = mat2(h11, -h01, -h01, h00) / det;
    // First rhs and zero-offset SAD (flow starts at 0, so the block is 0).
    vec2 rhs = vec2(0.0);
    float sad0 = 0.0;
    for (int j = -FLOW_R; j <= FLOW_R; j++) {
        for (int i = -FLOW_R; i <= FLOW_R; i++) {
            int c = (lc.y + j) * SH_STRIDE + lc.x + i;
            vec4 gx = (shTile[c + 1] - shTile[c - 1]) * 0.5;
            vec4 gy = (shTile[c + SH_STRIDE] - shTile[c - SH_STRIDE]) * 0.5;
            vec4 e = shTile[c] - shDiff[(dc.y + j) * DSH_STRIDE + dc.x + i];
            sad0 += dot(abs(e), vec4(0.25));
            rhs += vec2(dot(gx, e), dot(gy, e));
        }
    }
    vec2 flow = vec2(0.0);
    for (int it = 0; it < FLOW_ITERS; it++) {
        if (it > 0) {
            // Integer sampling keeps quads intact; the clamp is a no-op at
            // current constants and only guards the shared halo if
            // FLOW_ITERS ever grows.
            ivec2 block = clamp(ivec2(round(flow)),
                                ivec2(-(DAPRON - FLOW_R)), ivec2(DAPRON - FLOW_R));
            rhs = vec2(0.0);
            for (int j = -FLOW_R; j <= FLOW_R; j++) {
                for (int i = -FLOW_R; i <= FLOW_R; i++) {
                    int c = (lc.y + j) * SH_STRIDE + lc.x + i;
                    vec4 gx = (shTile[c + 1] - shTile[c - 1]) * 0.5;
                    vec4 gy = (shTile[c + SH_STRIDE] - shTile[c - SH_STRIDE]) * 0.5;
                    vec4 e = shTile[c] - shDiff[(dc.y + j + block.y) * DSH_STRIDE + dc.x + i + block.x];
                    rhs += vec2(dot(gx, e), dot(gy, e));
                }
            }
        }
        vec2 step = hinv * rhs;
        float len = length(step);
        if (len > 1.0) step /= len; // stay inside the linearization radius
        flow += step;
    }
    // Accepted displacement is capped by the shDiff halo.
    int m = clamp(max(1, int(flowMaxDisp + 0.5)), 1, FLOW_DISP_MAX);
    ivec2 block = clamp(ivec2(round(flow)), ivec2(-m), ivec2(m));
    if (block == ivec2(0)) return ivec2(0);
    // Block-match validation: the selected block must match the base window
    // better than the unrefined position both relatively and statistically.
    // On pure noise a shifted block can luck into a few percent lower SAD, so
    // the gain must also exceed k standard deviations of the window SAD
    // (SAD = 0.25*sum|e| over 4*(2R+1)^2 ~half-normal samples -> std ~
    // 0.151*sqrt(sum sigma^2); *1.25 folds in the accumulated base's noise).
    float sadB = 0.0;
    float sig2 = 0.0;
    for (int j = -FLOW_R; j <= FLOW_R; j++) {
        for (int i = -FLOW_R; i <= FLOW_R; i++) {
            int c = (lc.y + j) * SH_STRIDE + lc.x + i;
            vec4 b = shTile[c];
            vec4 e = b - shDiff[(dc.y + j + block.y) * DSH_STRIDE + dc.x + i + block.x];
            sadB += dot(abs(e), vec4(0.25));
            sig2 += dot(b, vec4(1.0)) * flowNoiseS + 4.0 * flowNoiseO;
        }
    }
    if (sadB > sad0 * FLOW_ACCEPT) return ivec2(0);
    if (sad0 - sadB < 2.0 * 0.19 * sqrt(sig2)) return ivec2(0);
    return block;
}

#define EPS 1e-6
#define EPS2 1e-5
void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    // Cooperative fill of both shared tiles (~14 texels per thread), then a
    // single group barrier makes them visible for LK and the combine pass.
    ivec2 groupOrigin = xy - ivec2(gl_LocalInvocationID.xy);
    for (int k = int(gl_LocalInvocationIndex); k < SH_SIZE; k += GROUP * GROUP) {
        shTile[k] = imageLoad(inTexture, groupOrigin + ivec2(k % SH_STRIDE, k / SH_STRIDE) - APRON);
    }
    for (int k = int(gl_LocalInvocationIndex); k < DSH_SIZE; k += GROUP * GROUP) {
        shDiff[k] = imageLoad(diffTexture, groupOrigin + ivec2(k % DSH_STRIDE, k / DSH_STRIDE) - DAPRON);
    }
    memoryBarrierShared();
    barrier();
    ivec2 lc = ivec2(gl_LocalInvocationID.xy) + APRON;
    ivec2 dc = ivec2(gl_LocalInvocationID.xy) + DAPRON;
    vec4 kernelParams = texture(kernelsMap, vec2(xy) / vec2(2.0 * vec2(textureSize(kernelsMap, 0)))).rgba;
    float s1 = max(kernelParams.x, EPS);
    float s2 = max(kernelParams.y, EPS);
    float rho = clamp(kernelParams.z, -1.0 + EPS, 1.0 - EPS);
    float det = max(1.0 - rho * rho, EPS);
    float a = 1.0 / (s1 * s1 * det);   // dy² (j) coefficient
    float b = -rho / (s1 * s2 * det);  // dy*dx (i*j) coefficient
    float c = 1.0 / (s2 * s2 * det);   // dx² (i) coefficient
    vec4 base = shTile[lc.y * SH_STRIDE + lc.x];
    ivec2 flow = (enableFlow == 1) ? refineFlow(xy, lc, dc) : ivec2(0);
    vec4 diff = shDiff[(dc.y + flow.y) * DSH_STRIDE + dc.x + flow.x];
    //vec4 bayer = getBayerVec(xy*2, inTex);
    float Z = 0.0001;
    float Z2 = 0.0;
    vec4 localDiff = vec4(0.0001);
    vec4 localDiffSigned = vec4(0.0);
    vec4 sumMain = vec4(0.0);
    vec4 sumSq = vec4(0.0);
    vec4 expThr = vec4(exposure * 0.99);
    for(float i = -5.0; i <= 5.0; i+=1.0) {
        // Row-constant terms of the anisotropic kernel exponent.
        float ci = c * i * i;
        float bi = 2.0 * b * i;
        for(float j = -5.0; j <= 5.0; j+=1.0) {
            // Local-translation assumption: the block selected at the center
            // applies to the whole combine window.
            vec4 neighborDiff = shDiff[(dc.y + int(j) + flow.y) * DSH_STRIDE + dc.x + int(i) + flow.x];
            vec4 neighborBayer = shTile[(lc.y + int(j)) * SH_STRIDE + lc.x + int(i)];
            // Mean and variance in one pass: E[x²] - E[x]² (max() guards the
            // fp cancellation floor; +1e-4/120 matches the old initializer).
            sumMain += neighborBayer;
            sumSq += neighborBayer * neighborBayer;
            if(any(greaterThan(neighborDiff, expThr))) {
                continue; // skip overexposed pixels
            }
            float w = exp(-(ci + bi * j + a * j * j));
            vec4 r = neighborDiff - neighborBayer;
            localDiff += abs(r) * w;
            localDiffSigned += r * w;
            Z += w;
            Z2 += w * w;
        }
    }
    vec4 meanMain = sumMain * (1.0 / 121.0);
    vec4 variance = (max(sumSq - sumMain * meanMain, vec4(0.0)) + vec4(0.0001)) / 120.0;
    float invZ = 1.0 / Z;
    localDiff *= invZ;
    vec4 localSigned = abs(localDiffSigned) * invZ;
    //float br = dot(base, vec4(0.25));
    // Residual noise: alter noise from the model (reined in by the measured
    // accumulated-base std, as before) plus the accumulated base's own std
    // estimated from the window variance.
    vec4 sigmaA = sqrt(max(meanMain * noiseS + noiseO, EPS));
    vec4 sigmaB = min(sqrt(variance), sigmaA); // averaging can only reduce noise
    sigmaA = min(sigmaA, sqrt(variance) * 2.0);
    vec4 sigmaR = sqrt(sigmaA * sigmaA + sigmaB * sigmaB);
    // Expected noise floors (E|X| = sqrt(2/pi)*sigma); the signed kernel mean's
    // floor shrinks by sqrt(Z2)/Z, the inverse effective tap count.
    vec4 absFloor = 0.7979 * sigmaR;
    vec4 signedFloor = absFloor * sqrt(Z2) * invZ;
    // Excess disagreement above the noise floor; max() of both statistics so
    // cancelling residuals around edges still raise the excess (anti-ghost).
    vec4 excess = max(max(localDiff - absFloor, localSigned - signedFloor), vec4(0.0));
    vec4 N = sigmaR;
    vec4 comb = (N * N) / (excess * excess + N * N);
    //vec4 comb = exp(-0.5 * (localDiff * localDiff) / (N * N));
    // One weight per Bayer quad: per-subpixel weights blend the color filters
    // by different amounts when their excess diverge in motion, shifting chroma.
    comb = robustWeight(comb);
    if(any(greaterThan(diff, vec4(exposure*0.80))) && exposure < 0.95) {
        comb = vec4(0.0); // skip overexposed pixels
    }
    imageStore(outTexture, xy, mix(base, diff, weight * comb));
}
