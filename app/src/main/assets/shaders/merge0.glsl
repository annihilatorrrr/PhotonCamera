#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
uniform highp usampler2D inTexture;
uniform highp usampler2D alterTexture;
uniform highp sampler2D alignmentTexture;
//layout(r16ui, binding = 0) uniform highp readonly uimage2D inTexture;
layout(rgba16f, binding = 0) uniform highp writeonly image2D outTexture;
uniform float whiteLevel;
uniform bool createDiff;
uniform float noiseS;
uniform float noiseO;
uniform ivec2 border;
#define TILE 2
#define CONCAT 1
#define M_PI 3.1415926535897932384626433832795
#define TILE_AL 16
float getBayer(ivec2 coords, highp usampler2D tex){
    //return float(imageLoad(inTexture,coords).r)/1023.f;
    return float(texelFetch(tex,coords,0).r);
}

vec4 getBayerVec(ivec2 coords, highp usampler2D tex){
    return vec4(getBayer(coords,tex),getBayer(coords+ivec2(1,0),tex),getBayer(coords+ivec2(0,1),tex),getBayer(coords+ivec2(1,1),tex))/whiteLevel;
}

float window(float x){
    return 0.5f - 0.5f * cos(2.f * M_PI * ((0.5f * (x + 0.5f) / float(TILE_AL))));
}

float windowxy(ivec2 xy){
    return window(float(xy.x)) * window(float(xy.y));
}

vec4 windowxy4(ivec2 xy){
    return vec4(window(float(xy.x)) * window(float(xy.y)),
                window(float(xy.x+1)) * window(float(xy.y)),
                window(float(xy.x)) * window(float(xy.y+1)),
                window(float(xy.x+1)) * window(float(xy.y+1)));
}

void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    ivec2 outSize = imageSize(outTexture);
    vec2 uvScale = vec2(outSize-border);
    vec2 uv = vec2(xy)/uvScale + vec2(0.5)/uvScale;
    vec4 bayer = getBayerVec(xy*TILE, inTexture);
    if (createDiff) {
        vec4 w[4];
        w[3] = windowxy4((TILE*xy)%TILE_AL);
        w[2] = windowxy4((TILE*xy)%TILE_AL + ivec2(TILE_AL,0));
        w[1] = windowxy4((TILE*xy)%TILE_AL + ivec2(0,TILE_AL));
        w[0] = windowxy4((TILE*xy)%TILE_AL + ivec2(TILE_AL));
        vec4 alignedSum = vec4(0.0);
        for (int i = 0; i < 4; i++) {
            vec2 align = texelFetch(alignmentTexture, ivec2((TILE*xy)/TILE_AL + ivec2(i % 2, i / 2)), 0).xy;
            //vec2 align = texture(alignmentTexture, uv + vec2(i % 2, i / 2) / uvScale).xy;
            ivec2 aligned = (xy + ivec2(align));
            aligned = ivec2(clamp(aligned, ivec2(0), ivec2(outSize - 1))) * TILE;
            vec4 bayerAlter = getBayerVec(aligned, alterTexture);
            alignedSum += bayerAlter * w[i];
        }
        bayer = alignedSum;
    }
    imageStore(outTexture, xy, bayer);
    //imageStore(outTexture, xy, vec4(0.5));
}