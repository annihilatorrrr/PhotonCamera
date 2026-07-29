precision highp sampler2D;
precision highp int;
precision highp float;
uniform sampler2D inTexture;
uniform vec4 exposure;
uniform float input1;
uniform float input2;
#define COL_R 1
#define COL_G 1
#define COL_B 1
#define COL_A 1
#define COL_CUSTOM 0
#define HISTSIZE 256
//#define HISTMPY 255.0
#define SCALE 1
#define HISTSTEPS uint(HISTSIZE/64)

#if COL_R == 1
layout(std430, binding = 1) buffer histogramRed {
    uint reds[];
};
shared uint localRed[HISTSIZE];
#endif
#if COL_G == 1
layout(std430, binding = 2) buffer histogramGreen {
    uint greens[];
};
shared uint localGreen[HISTSIZE];
#endif
#if COL_B == 1
layout(std430, binding = 3) buffer histogramBlue {
    uint blues[];
};
shared uint localBlue[HISTSIZE];
#endif
#if COL_A == 1
layout(std430, binding = 4) buffer histogramAlpha {
    uint alphas[];
};
shared uint localAlpha[HISTSIZE];
#endif

#define CUSTOM_PROGRAM //
#import median
#define LAYOUT //
LAYOUT

void main() {
ivec2 storePos = ivec2(gl_GlobalInvocationID.xy)*SCALE;
ivec2 imgsize = textureSize(inTexture,0).xy;
uint index = uint(gl_LocalInvocationIndex) * HISTSTEPS; // 0 - 64 * HISTSTEPS
for (uint i = 0u; i < HISTSTEPS; i++) {
#if COL_R == 1
        localRed[index + i] = 0u;
#endif
        #if COL_G == 1
        localGreen[index + i] = 0u;
#endif
        #if COL_B == 1
        localBlue[index + i] = 0u;
#endif
        #if COL_A == 1
        localAlpha[index + i] = 0u;
#endif
    }
barrier();

if (storePos.x < imgsize.x && storePos.y < imgsize.y) {
vec4 texColor = texture(inTexture,(vec2(storePos) + 0.5)/vec2(imgsize));
uvec4 texColorUint = clamp(uvec4(exposure * texColor), uvec4(0), uvec4(HISTSIZE - 1));
#if COL_CUSTOM == 1
        /*vec4 med[9];
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                med[(i+1)*3+(j+1)] = texture(inTexture,(vec2(storePos + ivec2(i, j)) + 0.5)/vec2(imgsize));
            }
        }
        vec4 medK = median9(med);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                vec4 diff = texture(inTexture,(vec2(storePos + ivec2(i,j)) + 0.5)/vec2(imgsize));
                vec4 sqDiff = (diff - medK) * (diff - medK);
                med[(i+1)*3+(j+1)] = sqDiff;
            }
        }
        vec4 variance = median9(med);*/

        // ------------------------------------------------------------
        // 1. Gather a 5x5 neighbourhood
        // ------------------------------------------------------------
        vec4 pixels[5][5];
        for (int i = -2; i <= 2; i++) {
        for (int j = -2; j <= 2; j++) {
        pixels[i+2][j+2] = texture(inTexture,
        (vec2(storePos + ivec2(i, j)) + 0.5) / vec2(imgsize));
        }
        }

        // ------------------------------------------------------------
        // 2. Compute median of 5x5 using overlapping 3x3 blocks
        //    (9 blocks, top‑left corners at offsets -2..0 in both axes)
        // ------------------------------------------------------------
        vec4 blockMedians[9];
        int idx = 0;
        for (int bi = 0; bi < 3; bi++) {          // top‑left row offset: -2, -1, 0
        for (int bj = 0; bj < 3; bj++) {      // top‑left col offset: -2, -1, 0
        vec4 block[9];
        int k = 0;
        for (int di = 0; di < 3; di++) {
        for (int dj = 0; dj < 3; dj++) {
        block[k++] = pixels[bi + di][bj + dj];
        }
        }
        blockMedians[idx++] = median9(block);
        }
        }
        vec4 medK = median9(blockMedians);   // approximate 5x5 median

        // ------------------------------------------------------------
        // 3. Compute squared deviations from medK
        // ------------------------------------------------------------
        vec4 sqDiff[5][5];
        for (int i = 0; i < 5; i++) {
                for (int j = 0; j < 5; j++) {
                        vec4 diff = pixels[i][j] - medK;
                        sqDiff[i][j] = diff * diff;
                }
        }

        // ------------------------------------------------------------
        // 4. Median of squared deviations (again via 3x3 blocks)
        // ------------------------------------------------------------
        vec4 varBlockMedians[9];
        idx = 0;
        for (int bi = 0; bi < 3; bi++) {
                for (int bj = 0; bj < 3; bj++) {
                        vec4 block[9];
                        int k = 0;
                        for (int di = 0; di < 3; di++) {
                                for (int dj = 0; dj < 3; dj++) {
                                block[k++] = sqDiff[bi + di][bj + dj];
                                }
                        }
                        varBlockMedians[idx++] = median9(block);
                }
        }

        vec4 variance = median9(varBlockMedians);   // approximate median of squared diffs

        float vmed[5];
        vmed[0] = variance.r;
        vmed[1] = variance.g;
        vmed[2] = variance.b;
        vmed[3] = variance.a;
        vmed[4] = dot(variance, vec4(0.25));
        float br = sqrt(dot(medK, vec4(0.25)) + 1e-8);
        float var = sqrt(median5(vmed) + 1e-8);
        uint brBin = uint(min(63.0, br * input1));
        uint varBin = uint(min(63.0, var * input2));
        uint combined = brBin * 64u + varBin;
        texColorUint = uvec4(combined, 0u, 0u, 0u);
#endif
        #if COL_R == 1
        atomicAdd(localRed[texColorUint.r], 1u);
#endif
        #if COL_G == 1
        atomicAdd(localGreen[texColorUint.g], 1u);
#endif
        #if COL_B == 1
        atomicAdd(localBlue[texColorUint.b], 1u);
#endif
        #if COL_A == 1
        atomicAdd(localAlpha[texColorUint.a], 1u);
#endif
    }
barrier();

for (uint i = 0u; i < HISTSTEPS; i++) {
#if COL_R == 1
        atomicAdd(reds[index + i], localRed[index + i]);
#endif
        #if COL_G == 1
        atomicAdd(greens[index + i], localGreen[index + i]);
#endif
        #if COL_B == 1
        atomicAdd(blues[index + i], localBlue[index + i]);
#endif
        #if COL_A == 1
        atomicAdd(alphas[index + i], localAlpha[index + i]);
#endif
    }
}