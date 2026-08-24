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

#define EPS 1e-6
void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    int cfa = clamp(cfaPattern, 0, 3);
    int chR = cfa;
    int chB = 3 - cfa;
    int g1 = (cfa == 0 || cfa == 3) ? 1 : 0;
    int g2 = (cfa == 0 || cfa == 3) ? 2 : 3;

    float ka[4];
    float kb[4];
    float kc[4];
    for(int k = 0; k < 4; ++k) {
        ivec2 sp = ivec2(k & 1, (k >> 1) & 1);
        vec4 kp = texture(kernelsMap, vec2(xy * 2 + sp) / vec2(4.0 * vec2(textureSize(kernelsMap, 0)))).rgba;
        float s1 = max(kp.x, EPS);
        float s2 = max(kp.y, EPS);
        float rho = clamp(kp.z, -1.0 + EPS, 1.0 - EPS);
        float det = max(1.0 - rho * rho, EPS);
        ka[k] = 1.0 / (s1 * s1 * det);   // dy² (j) coefficient
        kb[k] = -rho / (s1 * s2 * det);  // dy*dx (i*j) coefficient
        kc[k] = 1.0 / (s2 * s2 * det);   // dx² (i) coefficient
    }
    vec4 base = imageLoad(inTexture, xy);
    vec4 diff = imageLoad(diffTexture, xy);

    float sum4 = dot(base, vec4(1.0));
    float brR = base[chR];
    float brB = base[chB];
    float brG = 0.5 * (sum4 - brR - brB);
    vec3 planeBr = vec3(brR, brG, brB);
    vec3 planeNoise = sqrt(max(planeBr * noiseS + noiseO, vec3(EPS)));

    vec3 localDiff = vec3(0.0001);
    vec3 Z = vec3(0.0001);
    vec4 localDiff2 = vec4(0.0);
    vec4 Z2 = vec4(0.0);
    vec4 minD = vec4(1e9);
    vec4 maxD = vec4(-1e9);
    vec4 minB = vec4(1e9);
    vec4 maxB = vec4(-1e9);
    float dCache[121];
    float bCache[121];
    float wCache[121];
    int sCache[121];
    int cnt = 0;
    for(int di = -5; di <= 5; ++di) {
        int dx = di & 1;
        for(int dj = -5; dj <= 5; ++dj) {
            int dy = dj & 1;
            int sub = dx + 2 * dy;
            ivec2 qo = ivec2((di - dx) / 2, (dj - dy) / 2);
            vec4 neighborDiff = imageLoad(diffTexture, xy + qo);
            float d = neighborDiff[sub];
            if(d > exposure * 0.99) {
                continue;
            }
            vec4 neighborBase = imageLoad(inTexture, xy + qo);
            float bv = neighborBase[sub];
            float q = kc[sub] * float(di) * float(di) + 2.0 * kb[sub] * float(di) * float(dj) + ka[sub] * float(dj) * float(dj);
            float w = exp(-0.5 * q);
            minD[sub] = min(minD[sub], d);
            maxD[sub] = max(maxD[sub], d);
            minB[sub] = min(minB[sub], bv);
            maxB[sub] = max(maxB[sub], bv);
            dCache[cnt] = d;
            bCache[cnt] = bv;
            wCache[cnt] = w;
            sCache[cnt] = sub;
            cnt++;
        }
    }
    vec4 noise3 = vec4(3.0) * sqrt(max(base * noiseS + noiseO, vec4(EPS)));
    vec4 loD = minD - noise3;
    vec4 hiD = maxD + noise3;
    vec4 loB = minB - noise3;
    vec4 hiB = maxB + noise3;
    for(int i = 0; i < cnt; ++i) {
        int sub = sCache[i];
        float d = (dCache[i] - loD[sub]) / max(hiD[sub] - loD[sub], EPS) * (hiB[sub] - loB[sub]) + loB[sub];
        float bv = bCache[i];
        float w = wCache[i];
        int color = (sub == chR) ? 0 : (sub == chB) ? 2 : 1;
        localDiff[color] += abs(d - bv) * w;
        Z[color] += w;
        localDiff2[sub] += bv * w;
        Z2[sub] += w;
    }
    localDiff /= Z;
    planeNoise /= sqrt(Z + 1.0);

    vec3 comb = (planeNoise * planeNoise) / (localDiff * localDiff + planeNoise * planeNoise);
    float dG = max(diff[g1], diff[g2]);
    vec3 diffPlane = vec3(diff[chR], dG, diff[chB]);
    vec3 over = vec3(step(vec3(exposure * 0.99), diffPlane)) * float(exposure < 0.95);
    comb = mix(comb, vec3(0.0), over);

    vec4 mixW = vec4(
        (0 == chR) ? comb.x : (0 == chB) ? comb.z : comb.y,
        (1 == chR) ? comb.x : (1 == chB) ? comb.z : comb.y,
        (2 == chR) ? comb.x : (2 == chB) ? comb.z : comb.y,
        (3 == chR) ? comb.x : (3 == chB) ? comb.z : comb.y);
    imageStore(outTexture, xy, mix(base, diff, weight * mixW));
    //imageStore(outTexture, xy, localDiff2 / Z2); // blur test(check kernels)
}
