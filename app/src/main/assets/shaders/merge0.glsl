#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
uniform highp usampler2D inTexture;
uniform highp usampler2D alterTexture;
uniform highp sampler2D alignmentTexture;
//layout(r16ui, binding = 0) uniform highp readonly uimage2D inTexture;
layout(rgba16f, binding = 0) uniform highp readonly image2D avrTexture;
layout(rgba16f, binding = 1) uniform highp writeonly image2D outTexture;
layout(rgba8, binding = 2) uniform highp readonly image2D hotPixTexture;
layout(rgba16f, binding = 3) uniform highp readonly image2D baseTexture;


uniform float whiteLevel;
uniform vec4 blackLevel;
uniform float exposure;
uniform bool createDiff;
uniform float noiseS;
uniform float noiseO;
uniform ivec2 border;
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
    vec4 bayerBase = bayer;
    if (createDiff) {
        bayerBase = imageLoad(baseTexture,xy);
        //vec4 hp = imageLoad(hotPixTexture, xy);
        //bayer = bayer * vec4(1.0-hp) + imageLoad(avrTexture, xy) * hp;
        vec4 w[4];
        w[3] = windowxy4((TILE*xy)%TILE_AL);
        w[2] = windowxy4((TILE*xy)%TILE_AL + ivec2(TILE_AL,0));
        w[1] = windowxy4((TILE*xy)%TILE_AL + ivec2(0,TILE_AL));
        w[0] = windowxy4((TILE*xy)%TILE_AL + ivec2(TILE_AL));
        vec4 alignedSum = vec4(0.0);
        for (int i = 0; i < 4; i++) {
            ivec2 align = ivec2(texelFetch(alignmentTexture, ivec2((TILE*xy)/TILE_AL + ivec2(i % 2, i / 2)), 0).xy);
            //vec2 align = texture(alignmentTexture, uv + vec2(i % 2, i / 2) / uvScale).xy;
            ivec2 aligned = (xy + align);
            //ivec2 alignedFull = aligned * TILE;
            //aligned = ivec2(clamp(aligned, ivec2(0), ivec2(outSize - 1)));
            bvec2 lt = lessThan(aligned, ivec2(0));
            aligned = aligned * ivec2(not(lt)) + ivec2(-aligned) * ivec2(lt);
            /*if (aligned >= ivec2(outSize)) {
                aligned = 2 * ivec2(outSize) - aligned - 1;
            }*/
            bvec2 gt = greaterThan(aligned, ivec2(outSize - 1));
            aligned = (2 * ivec2(outSize) - aligned - 1) * ivec2(gt) + aligned * ivec2(not(gt));
            /*if (alignedFull != aligned) {
                alignedSum += bayer * w[i];
                continue;
            }*/
            vec4 bayerAlter = getBayerVec(aligned * TILE, alterTexture);
            //vec4 hp2 = imageLoad(hotPixTexture, aligned * TILE);
            //bayerAlter = bayerAlter * vec4(1.0-hp2) + imageLoad(avrTexture, aligned * TILE) * hp2;
            alignedSum += bayerAlter * w[i];

        }
        bayer = alignedSum;
        bayer *= vec4(exposure);
        float target = 0.95;
        if(exposure <= 0.9){
            target = 1.0;
        }
        if(any(greaterThan(bayer, vec4(target*exposure))) || any(greaterThan(bayerBase, vec4(target*exposure)))){
            bayer = bayerBase;
        }
        bayer = bayer-bayerBase;
    }
    imageStore(outTexture, xy, bayer);
}