#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
precision highp image2D;
uniform highp usampler2D inTexture;
uniform highp sampler2D gainMap;
layout(rgba16f, binding = 0) uniform highp writeonly image2D outTexture;


uniform float whiteLevel;
uniform vec4 blackLevel;
uniform float exposure;
#define TILE 2
#define CONCAT 1
#define M_PI 3.1415926535897932384626433832795
#define TILE_AL 16

uint getBayer(ivec2 coords, highp usampler2D tex){
    return texelFetch(tex,coords,0).r;
}

vec4 getBayerVec(ivec2 coords, highp usampler2D tex){
    vec4 c0 = vec4(getBayer(coords,tex),getBayer(coords+ivec2(1,0),tex),getBayer(coords+ivec2(0,1),tex),getBayer(coords+ivec2(1,1),tex));
    return clamp((c0 - blackLevel)/(vec4(whiteLevel)-blackLevel), 0.0, 1.0);
}

void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    vec4 bayer = getBayerVec(xy*TILE, inTexture);
    float gains = dot(texture(gainMap, vec2(xy)/vec2(imageSize(outTexture).xy)),vec4(0.25));
    //bayer = clamp(bayer*gains, vec4(0.0), vec4(1.0));
    imageStore(outTexture, xy, bayer * vec4(exposure));
}