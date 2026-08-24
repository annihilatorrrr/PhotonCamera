precision highp float;
precision highp sampler2D;
uniform highp sampler2D inTexture;
uniform highp sampler2D alignmentTexture;
uniform float whiteLevel;
uniform vec4 blackLevel;
uniform int yOffset;
// Sensor red-site offset ((cfa%2, cfa/2)); passed as a uniform because GLProg
// clears its define list after every program load, so a CFAPATTERN define set
// once at pipeline start would never reach this late-bound shader.
uniform ivec2 cfaShift;
#define WHITE_LEVEL 0.0
#define BLACK_LEVEL 0.0
#define TILE 2
#define CONCAT 1
out uint Output;


uvec4 getBayerVec(ivec2 coords){
    return uvec4(clamp(texelFetch(inTexture, coords, 0),0.0,1.0) * (vec4(WHITE_LEVEL)-vec4(BLACK_LEVEL)) + vec4(BLACK_LEVEL));
}
/*uvec4 getAlignmentVec(ivec2 coords){
    return uvec4(clamp(texelFetch(alignmentTexture, coords, 0),0.0,1.0) * (vec4(WHITE_LEVEL)-vec4(BLACK_LEVEL)) + vec4(BLACK_LEVEL));
}*/

uvec4 getAlignmentVec(ivec2 coords){
    vec2 value = texelFetch(alignmentTexture, coords, 0).xy;
    float dist = length(value);
    return uvec4(clamp(vec4(dist),0.0,1.0) * (vec4(WHITE_LEVEL)-vec4(BLACK_LEVEL)) + vec4(BLACK_LEVEL));
}


void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    xy += ivec2(0, yOffset);
    ivec2 alignSize = textureSize(alignmentTexture, 0);
    // Undo the merge00 packing shift: real raw site X lives at packed
    // rel = X + cfaShift (merge00 shifted quad origins by -cfaShift and
    // filled the out-of-range sites with edge duplicates, which land at
    // rel < cfaShift and rel > rawSize-1 and are simply never read here;
    // cfaShift is zero for RGGB/BGGR, so this is the identity for them).
    ivec2 rel = xy + cfaShift;
    uvec4 bayer = getBayerVec(rel / TILE);
    Output = bayer[(rel.x & 1) + (rel.y & 1) * TILE];
    /*if (xy.x < alignSize.x && xy.y < alignSize.y){
        uvec4 alignment = getAlignmentVec(xy);
        Output = alignment[x + y*TILE];
    }*/
}
