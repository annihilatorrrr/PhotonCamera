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
void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    vec4 base = imageLoad(inTexture, xy);
    vec4 diff = mix(vec4(0.0),imageLoad(diffTexture, xy), weight);
    imageStore(outTexture, xy, base + diff);
    //imageStore(outTexture, xy, vec4(0.5));
}
