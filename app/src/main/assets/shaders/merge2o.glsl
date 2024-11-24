precision highp float;
precision highp sampler2D;
uniform highp sampler2D inTexture;
uniform float whiteLevel;
uniform int yOffset;
#define TILE 2
#define CONCAT 1
out uint Output;

uvec4 getBayerVec(ivec2 coords){
    return uvec4(min(texelFetch(inTexture, coords, 0),1.0) * 65535.f);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    xy += ivec2(0, yOffset);
    int x = xy.x%TILE;
    int y = xy.y%TILE;
    uvec4 bayer = getBayerVec((xy/TILE));
    Output = bayer[x + y*TILE];
}
