precision highp float;
precision highp sampler2D;
uniform sampler2D inTexture;
uniform float whiteLevel;
uniform int yOffset;
#define TILE 2
#define CONCAT 1
out uint Output;

uvec4 getBayerVec(ivec2 coords){
    return uvec4(texelFetch(inTexture, coords, 0) * 65535.f);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    xy += ivec2(0, yOffset);
    int x = xy.x%TILE;
    int y = xy.y%TILE;
    uvec4 bayer = getBayerVec((xy/TILE)/4);
    Output = bayer[x + y*TILE];
}
