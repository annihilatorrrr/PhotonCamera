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

#define EPS 1e-6
#define EPS2 1e-5
void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    vec4 kernelParams = texture(kernelsMap, vec2(xy) / vec2(2.0 * vec2(textureSize(kernelsMap, 0)))).rgba;
    float s1 = max(kernelParams.x, EPS);
    float s2 = max(kernelParams.y, EPS);
    float rho = clamp(kernelParams.z, -1.0 + EPS, 1.0 - EPS);
    float det = max(1.0 - rho * rho, EPS);
    float a = 1.0 / (s1 * s1 * det);   // dy² (j) coefficient
    float b = -rho / (s1 * s2 * det);  // dy*dx (i*j) coefficient
    float c = 1.0 / (s2 * s2 * det);   // dx² (i) coefficient
    vec4 base = imageLoad(inTexture, xy);
    vec4 diff = imageLoad(diffTexture, xy);
    vec4 bayer = getBayerVec(xy*2, inTex);
    float Z = 0.0001;
    vec4 localDiff = vec4(0.0001);
    vec4 localDiff2 = vec4(0.0);
    //vec4 exposure1 = vec4(0.0);
    vec4 exposure2 = vec4(0.0);
    for(int i = -5; i <= 5; i++) {
        for(int j = -5; j <= 5; j++) {
            ivec2 offset = ivec2(i, j);
            ///vec4 neighborDiff = imageLoad(diffTexture, xy + offset);
            //vec4 neighborBayer = getBayerVec((xy + offset) * 2, inTex);
            vec4 neighborBayer = imageLoad(inTexture, xy + offset);
            //exposure1 += neighborDiff;
            exposure2 += neighborBayer;
        }
    }
    //exposure1 /= 121.0;
    exposure2 /= 121.0;
    vec4 meanMain = exposure2;
    vec4 variance = vec4(0.0001);
    for(float i = -5.0; i <= 5.0; i+=1.0) {
        for(float j = -5.0; j <= 5.0; j+=1.0) {
            ivec2 offset = ivec2(i, j);
            vec4 neighborDiff = imageLoad(diffTexture, xy + offset);
            //vec4 neighborBayer = getBayerVec((xy + offset) * 2, inTex);
            vec4 neighborBayer = imageLoad(inTexture, xy + offset);
            //variance = max(((neighborBayer-meanMain)*(neighborBayer-meanMain)), variance);
            variance += ((neighborBayer-meanMain)*(neighborBayer-meanMain));
            if(any(greaterThan(neighborDiff, vec4(exposure*0.99)))) {
                continue; // skip overexposed pixels
            }
            float q = c * i * i + 2.0 * b * i * j + a * j * j;
            float w = exp(-1.0 * q);
            localDiff += abs(neighborDiff-neighborBayer) * w;
            localDiff2 += neighborBayer * w;
            Z += w;
        }
    }
    variance /= 120.0;
    localDiff /= Z;
    //float br = dot(base, vec4(0.25));
    vec4 N = sqrt(max(meanMain * noiseS + noiseO, EPS));
    N = min(N, sqrt(variance)*2.0); // Get noise floor from flat areas to improve noise model robustness
    //N /= sqrt(Z + 1.0);
    vec4 comb = (N * N) / (localDiff * localDiff + N * N);
    //vec4 comb = exp(-0.5 * (localDiff * localDiff) / (N * N));
    if(any(greaterThan(diff, vec4(exposure*0.80))) && exposure < 0.95) {
        comb = vec4(0.0); // skip overexposed pixels
    }
    imageStore(outTexture, xy, mix(base, diff, weight * comb));
    //imageStore(outTexture, xy, localDiff2/Z); // blur test(check kernels)
}
