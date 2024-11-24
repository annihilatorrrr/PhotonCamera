#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
uniform highp usampler2D inTexture;
//uniform highp sampler2D diffTexture;
layout(rgba16f, binding = 0) uniform highp readonly image2D diffTexture;
layout(rgba16f, binding = 1) uniform highp writeonly image2D outTexture;
uniform float whiteLevel;
uniform float noiseS;
uniform float noiseO;

shared float s_prev[10][10];    // 8x8 block + 1 pixel border
shared float s_curr[10][10];    // 8x8 block + 1 pixel border

const float EPSILON = 0.01;
const int WINDOW_SIZE = 3;
const int MAX_ITERATIONS = 10;
const float EPS = 1e-6;

float getBayer(ivec2 coords, highp usampler2D tex){
    return float(texelFetch(tex,coords,0).r);
}

vec4 getBayerVec(ivec2 coords, highp usampler2D tex){
    return vec4(getBayer(coords,tex),getBayer(coords+ivec2(1,0),tex),getBayer(coords+ivec2(0,1),tex),getBayer(coords+ivec2(1,1),tex))/whiteLevel;
}

// Calculate spatial and temporal derivatives
void calculateDerivatives(ivec2 lid, out vec2 spatial, out float temporal) {
    // Calculate centered derivatives
    lid = clamp(lid, ivec2(0), ivec2(7));
    float dx = (s_curr[lid.y + 1][lid.x + 2] - s_curr[lid.y + 1][lid.x]) * 0.5;
    float dy = (s_curr[lid.y + 2][lid.x + 1] - s_curr[lid.y][lid.x + 1]) * 0.5;
    float dt = s_curr[lid.y + 1][lid.x + 1] - s_prev[lid.y + 1][lid.x + 1];

    spatial = vec2(dx, dy);
    temporal = dt;
}

void loadSharedMemory(ivec2 gid, ivec2 lid) {
    // Load 16x16 block plus borders
    for(int dy = -1; dy <= 1; dy++) {
        for(int dx = -1; dx <= 1; dx++) {
            ivec2 pos = gid + ivec2(dx, dy);
            ivec2 shared_pos = lid + ivec2(dx + 1, dy + 1);

            // Clamp coordinates to image bounds
            pos = clamp(pos, ivec2(0), imageSize(outTexture) - ivec2(1));

            // Load luminance values
            s_prev[shared_pos.y][shared_pos.x] = dot(getBayerVec(pos*2,inTexture), vec4(0.25));
            s_curr[shared_pos.y][shared_pos.x] = dot(imageLoad(diffTexture, pos),vec4(0.25));
            //s_curr[shared_pos.y][shared_pos.x] = dot(texelFetch(diffTexture, pos,0),vec4(0.25));
        }
    }
}


void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    ivec2 lid = ivec2(gl_LocalInvocationID.xy);
    vec2 uvSize = vec2(1.0)/vec2(imageSize(outTexture));
    vec2 uv = vec2(xy)*uvSize + vec2(0.5)*uvSize;

    loadSharedMemory(xy, lid);
    barrier();
    memoryBarrierShared();
    // Lucas-Kanade iteration variables
    vec2 flow = vec2(0.0);
    mat2 A = mat2(0.0);
    vec2 b = vec2(0.0);

    // Iterate to refine flow estimate
    for(int iter = 0; iter < MAX_ITERATIONS; iter++) {
        A = mat2(0.0);
        b = vec2(0.0);

        // Accumulate over window
        for(int wy = -WINDOW_SIZE/2; wy <= WINDOW_SIZE/2; wy++) {
            for(int wx = -WINDOW_SIZE/2; wx <= WINDOW_SIZE/2; wx++) {
                ivec2 wpos = lid + ivec2(wx + 1, wy + 1);

                vec2 spatial;
                float temporal;
                calculateDerivatives(wpos, spatial, temporal);

                // Build system of equations
                A[0][0] += spatial.x * spatial.x;
                A[0][1] += spatial.x * spatial.y;
                A[1][0] += spatial.x * spatial.y;
                A[1][1] += spatial.y * spatial.y;

                b += spatial * (temporal + dot(spatial, flow));
            }
        }

        // Solve 2x2 system using Cramer's rule
        float det = A[0][0] * A[1][1] - A[0][1] * A[1][0];
        if(abs(det) > EPSILON) {
            vec2 delta = vec2(
                (A[1][1] * b.x - A[0][1] * b.y) / det,
                (-A[1][0] * b.x + A[0][0] * b.y) / det
            );

            flow -= delta;

            // Early termination if flow update is small
            if(dot(delta, delta) < EPSILON) {
                break;
            }
        } else {
            // Matrix is singular, cannot solve
            flow = vec2(0.0);
            break;
        }
    }
    vec4 diff = imageLoad(diffTexture, xy-ivec2(flow))-getBayerVec(xy*2,inTexture);
    //vec4 diff = imageLoad(diffTexture, xy)-getBayerVec(xy*2,inTexture);
    //vec4 diff = texture(diffTexture, uv+flow*uvSize)-getBayerVec(xy*2,inTexture);
    imageStore(outTexture, xy, diff);
    /*
    vec4 base = getBayerVec(xy*2,inTexture);
    vec4 minDiffCenter = imageLoad(diffTexture, xy-ivec2(flow))-base;
    vec4 diffBase = minDiffCenter;
    vec4 noise = sqrt(noiseS*base + noiseO);
    vec4 Z = vec4(0.0);
    vec4 diffSum = vec4(0.0);
    vec4 cc[4];
    for (int i = 0; i < 3; i++) {
        for (int j = 0; j < 3; j++) {
            ivec2 pos = ivec2(i,j);
            ivec2 pos2 = ivec2(-i,-j);
            ivec2 pos3 = ivec2(i,-j);
            ivec2 pos4 = ivec2(-i,j);
            cc[0] = vec4(imageLoad(diffTexture, xy+pos-ivec2(flow))-base);
            cc[1] = vec4(imageLoad(diffTexture, xy+pos2-ivec2(flow))-base);
            cc[2] = vec4(imageLoad(diffTexture, xy+pos3-ivec2(flow))-base);
            cc[3] = vec4(imageLoad(diffTexture, xy+pos4-ivec2(flow))-base);
            // Compute the weights
            vec4 d = vec4(length(cc[0]-diffBase),length(cc[1]-diffBase),length(cc[2]-diffBase),length(cc[3]-diffBase));
            vec4 w = (1.0-d*d/(d*d + noise));
            float wm = min(min(min(w[0],w[1]),w[2]),w[3]);
            w -= wm*0.999;
            vec4 alignedV = mat4(cc[0],cc[1],cc[2],cc[3])*w;
            vec4 d2 = vec4(1.0)/(abs(alignedV-diffBase)+noise/8.0);
            diffSum += alignedV*d2;
            Z += dot(vec4(d2),w);
        }
    }
    imageStore(outTexture, xy, diffSum/Z);*/
}
