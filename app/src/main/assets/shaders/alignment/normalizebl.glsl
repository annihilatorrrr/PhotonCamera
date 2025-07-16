#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
precision highp image2D;
uniform highp sampler2D baseTexture;
uniform highp sampler2D gainMap;
layout(rgba16f, binding = 0) uniform highp writeonly image2D outTexture;
uniform vec4 blackLevel;
uniform float whiteLevel;
#define TILE 2
#define CONCAT 1
#define M_PI 3.1415926535897932384626433832795
#define TILE_AL 16

void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    vec4 bayer = texelFetch(baseTexture, xy, 0);
    float gains = dot(texture(gainMap, vec2(xy)/vec2(imageSize(outTexture).xy)),vec4(0.25));
    bayer = clamp((bayer - blackLevel) / (vec4(whiteLevel) - blackLevel), 0.0, 1.0);
    imageStore(outTexture, xy, bayer);
}