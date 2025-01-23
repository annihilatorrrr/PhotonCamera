#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 1) uniform highp readonly image2D diffTexture;
layout(rgba16f, binding = 2) uniform highp writeonly image2D outTexture;
#define TILE 2
#define CONCAT 1
uniform float weight;
uniform float noiseS;
uniform float noiseO;
#define EPS 1e-6
void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    vec4 base = imageLoad(inTexture, xy);
    /*vec4 noise = sqrt(max(base * noiseS + noiseO,EPS));
    vec4 diffM[9];
    for (int i = 0; i < 9; i++) {
        ivec2 off = ivec2(i % 3 - 1, i / 3 - 1);
        diffM[i] = imageLoad(diffTexture, xy + off);
    }
    vec4 diffF = median9(diffM);
    bvec4 mask = lessThan(abs(diffF - base), noise);*/
    vec4 diff = imageLoad(diffTexture, xy)*weight;
    imageStore(outTexture, xy, base + diff);
    //imageStore(outTexture, xy, vec4(0.5));
}
