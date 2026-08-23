
#define SCALE 4
precision highp float;
precision highp sampler2D;
uniform sampler2D LowresInput;
uniform sampler2D GuideHigh;
out vec3 Output;
// Guided upsampling, same technique as the FusionMap upsampling in initial.glsl:
// a Gaussian-weighted least-squares fit of the lowres input against the highres
// guide lightness, evaluated with the center pixel's guide lightness.
// The window radius equals SCALE so the fit always spans the same 3x3 parent
// texels as initial.glsl (radius 2 at scale 2), independent of the scale.
void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec2 lowSize = vec2(textureSize(LowresInput, 0));
    ivec2 highMax = textureSize(GuideHigh, 0) - ivec2(1);
    const int R = SCALE;
    // Gaussian spatial kernel, computed inline: wi lives in one register across
    // the j loop, wj is recomputed per iteration (cheaper than array indexing)
    const float sigma = 0.4 * float(R);
    const float sigmaSq2 = 2.0 * sigma * sigma;
    float momentX  = 0.0;
    vec3  momentY  = vec3(0.0);
    float momentX2 = 0.0;
    vec3  momentXY = vec3(0.0);
    float ws = 0.0;
    for (int i = -R; i <= R; i++) {
        float wi = exp(-float(i * i) / sigmaSq2);
        for (int j = -R; j <= R; j++) {
            float w = wi * exp(-float(j * j) / sigmaSq2);
            ivec2 pos = clamp(xy + ivec2(i, j), ivec2(0), highMax);
            float lightness = dot(texelFetch(GuideHigh, pos, 0).rgb, vec3(1.0/3.0));
            // Bilinear lowres lookup. gaussdown anchors lowres texel (i,j) at
            // highres (i*SCALE, j*SCALE), so highres p maps to continuous lowres
            // coord p/SCALE. Keeps the fitted a,b continuous across block
            // boundaries, avoiding rectangular seams.
            vec3 lowresVal = textureLod(LowresInput, (vec2(pos) / float(SCALE) + 0.5) / lowSize, 0.0).rgb;
            momentX  += lightness * w;
            momentY  += lowresVal * w;
            momentX2 += lightness * lightness * w;
            momentXY += lightness * lowresVal * w;
            ws       += w;
        }
    }
    float invWs = 1.0 / ws;
    momentX *= invWs; momentY *= invWs; momentX2 *= invWs; momentXY *= invWs;
    float meanX = momentX;
    vec3  meanY = momentY;
    float varX  = momentX2 - meanX * meanX;
    vec3  covXY = momentXY - meanX * meanY;
    // Handle zero variance case with epsilon for stability
    vec3 a = covXY / (max(varX, 0.0) + 3e-04);
    vec3 b = meanY - a * meanX;
    float guideLightness = dot(texelFetch(GuideHigh, xy, 0).rgb, vec3(1.0/3.0));
    Output = a * guideLightness + b;
    Output = normalize(Output) * length(guideLightness);
}
