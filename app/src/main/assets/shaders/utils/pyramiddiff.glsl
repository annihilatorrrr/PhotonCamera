precision highp float;
precision highp sampler2D;
uniform sampler2D target;
uniform sampler2D base;
uniform ivec2 size;
uniform ivec2 size2;
out vec4 result;
#import interpolation

void main() {
    vec2 uv = gl_FragCoord.xy / vec2(size);
    result = textureBicubicHardware(target, uv) - textureBicubicHardware(base, uv);
}
