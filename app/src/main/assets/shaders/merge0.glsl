#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
uniform usampler2D inTexture;
uniform usampler2D alterTexture;
//layout(r16ui, binding = 0) uniform highp readonly uimage2D inTexture;
layout(rgba16f, binding = 0) uniform highp writeonly image2D outTexture;
uniform float whiteLevel;
uniform bool createDiff;
#define TILE 2
#define CONCAT 1

float getBayer(ivec2 coords, usampler2D tex){
    //return float(imageLoad(inTexture,coords).r)/1023.f;
    return float(texelFetch(tex,coords,0).r)/whiteLevel;
}

vec4 getBayerVec(ivec2 coords, usampler2D tex){
    return vec4(getBayer(coords,tex),getBayer(coords+ivec2(1,0),tex),getBayer(coords+ivec2(0,1),tex),getBayer(coords+ivec2(1,1),tex));
}

void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    vec4 bayer = getBayerVec(xy*TILE, inTexture);
    if (createDiff){
        bayer = getBayerVec(xy*TILE, alterTexture)-bayer;
    }
    imageStore(outTexture, xy, bayer);
    //imageStore(outTexture, xy, vec4(0.5));
}
