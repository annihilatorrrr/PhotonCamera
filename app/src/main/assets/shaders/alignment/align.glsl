#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
precision highp image2D;
uniform highp sampler2D prevAlignment;
uniform highp sampler2D baseTexture;
uniform highp sampler2D alterTexture;
uniform highp sampler2D baseCurve;
uniform highp sampler2D alterCurve;
layout(rgba16f, binding = 0) uniform highp writeonly image2D outTexture;

uniform float noiseS;
uniform float noiseO;
uniform int first;
uniform ivec2 rawHalf;
uniform float exposure;
uniform float integralNorm;
uniform float significancy;

#define TILE_AL 16
#define TILE (TILE_AL/2)
#define M_PI 3.1415926535897932384626433832795
#define OFFSETS 5
// Robust noise-normalized cost: differences are divided by the local noise
// sigma (shot+read, scaled to the current pyramid level) and truncated at
// TRUNC sigma so single-pixel outliers (hot pixels, clipping edges) cannot
// dominate the tile sum. This keeps the cost surface informative on dark,
// noise-dominated night scenes where raw SAD is flat and the argmin is noise.
#define ROBUST 1
#define TRUNC 3.0
#import median

shared mat4 inputDifferences[TILE*TILE]; // use this to store the 3x3 search grid images differences

vec4 getPixel(ivec2 coords, highp sampler2D tex) {
    return texelFetch(tex, coords, 0);
}

vec4 getPixelLaplacian(ivec2 coords, highp sampler2D tex) {
    ivec2 size = textureSize(tex, 0);
    coords = clamp(coords, ivec2(1), size - ivec2(2));
    vec4 center = texelFetch(tex, coords, 0);
    vec4 left = texelFetch(tex, coords + ivec2(-1, 0), 0);
    vec4 right = texelFetch(tex, coords + ivec2(1, 0), 0);
    vec4 up = texelFetch(tex, coords + ivec2(0, -1), 0);
    vec4 down = texelFetch(tex, coords + ivec2(0, 1), 0);
    return (left + right + up + down) - center * 3.0;
}

highp vec4 getAlignment(ivec2 coords) {
    coords = clamp(coords, ivec2(0), ivec2(textureSize(baseTexture, 0)/TILE_AL - 1));
    return texelFetch(prevAlignment, coords, 0);
}

highp vec4 alignmentToVec4(highp vec2 alignment) {
    highp vec4 converted = vec4(floor(alignment.x), floor(alignment.y), fract(alignment.x), fract(alignment.y));
    converted.xy /= vec2(rawHalf);
    return converted;
}

highp vec2 vec4ToAlignment(highp vec4 alignment) {
    // Round the integer part: it is stored as floor(v)/rawHalf in an rgba16f
    // texture, and half-float precision reconstructs e.g. 2/480*480 as 1.9998.
    // Truncating that silently biases offsets by -1px. The fract part
    // (subpixel residual) is preserved for callers, which must floor().
    return floor(alignment.xy*vec2(rawHalf) + vec2(0.5)) + alignment.zw;
}

float brightness(vec4 color) {
    return dot(color, vec4(0.25));
}

// Per-pixel noise sigma at this pyramid level for the given base brightness.
#if ROBUST
float levelNoise(float baseBrightness) {
    // sigma per frame; the base-alter difference has sqrt(2) larger sigma,
    // which is folded into the significancy threshold instead of here.
    return max(sqrt(max(baseBrightness, 0.0) * noiseS + noiseO) / integralNorm, 1e-5);
}
float robustCost(vec4 baseValue, vec4 alterValue, float sigma) {
    vec4 d = abs(baseValue - alterValue);
    #if ROBUST == 2
    // Charbonnier: smooth truncation, no hard cliff
    vec4 eps = vec4(sigma * 0.35);
    vec4 c = sqrt(d * d + eps * eps) - eps;
    return dot(min(c, vec4(TRUNC * sigma)) / vec4(sigma), vec4(0.25));
    #elif ROBUST == 3
    // Noise-normalized squared difference (Gaussian NLL): correct alignment
    // sits on the chi-square noise floor (~2 per channel), a wrong one adds
    // signal^2/sigma^2. Separates signal from the noise floor far better than
    // L1 on dark scenes.
    vec4 nd = (d * d) / vec4(sigma * sigma);
    return dot(min(nd, vec4(TRUNC * TRUNC)), vec4(0.25));
    #else
    // Hard truncation at TRUNC sigma
    return dot(min(d, vec4(TRUNC * sigma)) / vec4(sigma), vec4(0.25));
    #endif
}
#endif

mat4 getSharedDifferences(ivec2 xy, ivec2 prevOffset) {
    mat4 differences;
    vec4 baseValue = clamp(getPixel(xy, baseTexture), 0.000, 1.0);
    float baseBrightness = brightness(baseValue);
    #if ROBUST
    float sigma = levelNoise(baseBrightness);
    #endif
    // Base pixel unusable (clipped above the alter frame's exposure or below
    // the black floor): contribute a neutral 0 cost to every candidate so the
    // tile keeps the previous alignment instead of locking onto garbage.
    float baseWeight = (baseBrightness > brightness(clamp(baseValue, 0.0, exposure)) || baseBrightness < 0.001) ? 0.0 : 1.0;
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            vec4 alterValue = clamp(getPixel(xy + ivec2(i-1, j-1) + prevOffset, alterTexture), 0.0, exposure);
            #if ROBUST
            differences[i][j] = robustCost(baseValue, alterValue, sigma) * baseWeight;
            #else
            differences[i][j] = dot(abs(baseValue - alterValue), vec4(0.25));
            #endif
        }
    }
    #if !ROBUST
    if (baseBrightness > brightness(clamp(baseValue, 0.0, exposure)) || baseBrightness < 0.001) {
        differences *= 0.0;
    }
    #endif
    return differences;
}

mat4 getOffsetDifferences(ivec2 xy) {
    mat4 differences;
    vec4 baseValue = clamp(getPixel(xy, baseTexture), 0.000, 1.0);
    float baseBrightness = brightness(baseValue);
    #if ROBUST
    float sigma = levelNoise(baseBrightness);
    #endif
    float baseWeight = (baseBrightness > brightness(clamp(baseValue, 0.0, exposure)) || baseBrightness < 0.001) ? 0.0 : 1.0;
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            vec2 prevOffset = vec4ToAlignment(getAlignment(xy/(2*TILE) + ivec2(i-1, j-1)))*2.0;
            if(i == 3 && j == 3) {
                prevOffset = vec2(0.0);
            }
            // floor, not ivec2() truncation: prevOffset may carry a subpixel
            // fract and trunc rounds the wrong way for negative offsets
            vec4 alterValue = clamp(getPixel(xy + ivec2(floor(prevOffset)), alterTexture), 0.0, exposure);
            #if ROBUST
            differences[i][j] = robustCost(baseValue, alterValue, sigma) * baseWeight;
            #else
            differences[i][j] = dot(abs(baseValue - alterValue), vec4(0.25));
            #endif
        }
    }
    #if !ROBUST
    if (baseBrightness > brightness(clamp(baseValue, 0.0, exposure)) || baseBrightness < 0.001) {
        differences *= 0.0;
    }
    #endif
    return differences;
}

highp vec2 getPrevOffset(ivec2 tile_xy) {
    ivec2 localOffsets[OFFSETS];
    localOffsets[0] = ivec2(0, 0);
    localOffsets[1] = ivec2(1, 0);
    localOffsets[2] = ivec2(-1, 0);
    localOffsets[3] = ivec2(0, 1);
    localOffsets[4] = ivec2(0, -1);
#if OFFSETS > 5
    localOffsets[5] = ivec2(-1, -1);
    localOffsets[6] = ivec2(-1, 1);
    localOffsets[7] = ivec2(1, -1);
    localOffsets[8] = ivec2(1, 1);
#endif
    vec2 prevOffset = vec2(0.0);
    // Local thread ID within work group
    ivec2 localID = ivec2(gl_LocalInvocationID.xy) - ivec2(TILE/2, TILE/2); // 0 - TILE-1
    int localIndex = int(gl_LocalInvocationIndex); // 0 - TILE*TILE-1
    // Get previous alignment if not first level
    // split to 4 calls to increase scan window size
    // Decrease inputDifferences size to TILE*TILE
    mat4 temp = mat4(0.0);
    for (int i = 0; i < OFFSETS; i++) {
        temp += getOffsetDifferences((tile_xy+localOffsets[i]) * TILE + localID);
    }
    inputDifferences[localIndex] = temp;
    barrier();
    mat4 sum = mat4(0.0);
    // Parallel reduction for summing
    for (int stride = TILE * TILE / 2; stride > 0; stride >>= 1) {
        if (localIndex < stride) {
            inputDifferences[localIndex] += inputDifferences[localIndex + stride];
        }
        barrier();
    }

    sum = inputDifferences[0];
    // Use mat4 sum to find the best offset from (-1,-1) to (1,1)
    vec2 bestOffset = vec2(0.0);
    float minDiff = sum[0][0];

    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            if (sum[i][j] < minDiff) {
                minDiff = sum[i][j];
                if(i == 3 && j == 3) {
                    bestOffset = vec2(0.0);
                } else {
                    bestOffset = vec2(i - 1, j - 1);
                }
            }
        }
    }
    prevOffset = vec4ToAlignment(getAlignment(tile_xy / 2 + ivec2(bestOffset))) * 2.0;
    return prevOffset;
}

// Compute alignment between base and alter textures
highp vec3 computeAlignment(ivec2 tile_xy, vec2 prevOffset) {
    // Fill inputDifferences array with 4 calls to getSharedDifferences
    ivec2 localOffsets[OFFSETS];
    localOffsets[0] = ivec2(0, 0);
    localOffsets[1] = ivec2(1, 0);
    localOffsets[2] = ivec2(-1, 0);
    localOffsets[3] = ivec2(0, 1);
    localOffsets[4] = ivec2(0, -1);
#if OFFSETS > 5
    localOffsets[5] = ivec2(-1, -1);
    localOffsets[6] = ivec2(-1, 1);
    localOffsets[7] = ivec2(1, -1);
    localOffsets[8] = ivec2(1, 1);
#endif
    // Local thread ID within work group
    ivec2 localID = ivec2(gl_LocalInvocationID.xy) - ivec2(TILE/2, TILE/2); // 0 - TILE-1
    int localIndex = int(gl_LocalInvocationIndex); // 0 - TILE*TILE-1
    // split to 4 calls to increase scan window size and sum calls
    mat4 temp = mat4(0.0);
    for (int i = 0; i < OFFSETS; i++) {
        int targetIndex = localIndex + i * TILE*TILE;
        temp += getSharedDifferences((tile_xy+localOffsets[i]) * TILE + localID, ivec2(floor(prevOffset)));
    }
    inputDifferences[localIndex] = temp;
    // Ensure all threads have written to shared memory
    barrier();
    // Sum the differences to get final mat4 sum
    mat4 sum = mat4(0.0);
    // Parallel reduction for summing
    for (int stride = TILE * TILE / 2; stride > 0; stride >>= 1) {
        if (localIndex < stride) {
            inputDifferences[localIndex] += inputDifferences[localIndex + stride];
        }
        barrier();
    }
    // First thread has the final sum
    sum = inputDifferences[0];
    // Use mat4 sum to find the best offset from (-1,-1) to (1,1)
    highp vec2 bestOffset = prevOffset;
    float minDiff = sum[0][0];

    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            if (sum[i][j] < minDiff) {
                minDiff = sum[i][j];
                bestOffset = prevOffset + vec2(i-1, j-1);
            }
        }
    }
#if ROBUST
    // Significance gate: compare the cost improvement of the best candidate
    // over the previous alignment against the statistical noise of the summed
    // cost (CLT: std of the sum ~ sqrt(expected cost * N)). With the
    // noise-normalized cost a correctly aligned tile averages ~1.1 (L1) or ~2
    // (chi-square, ROBUST 3). If the improvement is below 'significancy'
    // standard deviations, the minimum is noise and we keep the previous
    // alignment. This stops textureless night tiles from random-walking into
    // blocky misalignment. 'sum' comes from shared memory and is identical on
    // every thread, so the gate keeps the returned offset uniform.
    {
        float n = float(OFFSETS * TILE * TILE);
        float expected = (ROBUST == 3) ? 2.0 : 1.13; // mean per-pixel cost when aligned
        float thresh = significancy * sqrt(expected / n);
        float costPrev = sum[1][1];
        float improvement = (costPrev - minDiff) / n;
        if (improvement < thresh) {
            bestOffset = prevOffset;
            minDiff = costPrev;
        }
    }
#endif
    return vec3(bestOffset.x, bestOffset.y, minDiff);
}

void main() {
    //ivec2 tile_xy = ivec2(gl_GlobalInvocationID.xy)/TILE;
    ivec2 tile_xy = ivec2(gl_WorkGroupID.xy);
    int localIndex = int(gl_LocalInvocationIndex);
    // Get previous offset
    vec2 prevOffset = vec2(0.0);
    if(first == 0) {
        prevOffset = getPrevOffset(tile_xy);
    }

    // Compute alignment vector
    vec3 bestOffset = computeAlignment(tile_xy, prevOffset);
    bestOffset = computeAlignment(tile_xy, bestOffset.xy);
    //bestOffset = computeAlignment(tile_xy, bestOffset.xy);
    if (localIndex == 0) {
        // Store the best offset in the output texture
        imageStore(outTexture, tile_xy, alignmentToVec4(bestOffset.xy));
    }
}
