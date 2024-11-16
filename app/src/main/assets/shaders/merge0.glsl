#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
uniform usampler2D inTexture;
//layout(r16ui, binding = 0) uniform highp readonly uimage2D inTexture;
layout(rgba16f, binding = 0) uniform highp writeonly image2D outTexture;
uniform float whiteLevel;
#define TILE 2
#define CONCAT 1

float getBayer(ivec2 coords){
    //return float(imageLoad(inTexture,coords).r)/1023.f;
    return float(texelFetch(inTexture,coords,0).r)/whiteLevel;
}

vec4 getBayerVec(ivec2 coords){
    return vec4(getBayer(coords),getBayer(coords+ivec2(1,0)),getBayer(coords+ivec2(0,1)),getBayer(coords+ivec2(1,1)));
}

void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    vec4 bayer = getBayerVec(xy*TILE);
    imageStore(outTexture, xy, bayer);
    //imageStore(outTexture, xy, vec4(0.5));
}
