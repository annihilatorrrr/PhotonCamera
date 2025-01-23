#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
//uniform sampler2D RawBuffer;
//uniform sampler2D GreenBuffer;
layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 1) uniform highp readonly image2D greenTexture;
layout(rgba16f, binding = 2) uniform highp readonly image2D igTexture;
layout(rgba16f, binding = 3) uniform highp writeonly image2D outTexture;
uniform int yOffset;
//out vec3 Output;
//#define EPS 0.0001
//#define EPS2 0.001
#define EPS 1e-6
#define EPS2 1e-6
#define alpha 3.75
#define NOISEO 0.0
#define NOISES 0.0
//#define BETA 0.42
#define BETA 0.0
//#define THRESHOLD 1.9
#define THRESHOLD 1.9

#define L 3
//#define greenmin (0.08)
#define greenmin (0.002)
//#define greenmax (0.9)
#define greenmax (0.999)
// Helper function to determine Bayer pattern at a given position
// 0: R, 1: G (at red row), 2: G (at blue row), 3: B
int getBayerPattern(ivec2 pos) {
    int x = (pos.x) % 2;
    int y = (pos.y) % 2;
    return (y << 1) | x;
}

float getBayerSample(ivec2 pos) {
    return imageLoad(inTexture, pos).r;
    //return float(texelFetch(RawBuffer, pos, 0).x);
}

vec2 gr(ivec2 pos){
    return imageLoad(greenTexture, pos).rg;
    //return texelFetch(GreenBuffer, pos, 0).xy;
}

float bayer(ivec2 pos){
    return getBayerSample(pos);
    //return float(texelFetch(RawBuffer, pos, 0).x);
}

float dgr(ivec2 pos){
    return gr(pos).x - bayer(pos);
}


float dxy(ivec2 pos, ivec2 stp) {
    return abs((4.0 * getBayerSample(pos) - 3.0 * getBayerSample(pos + stp) - 3.0 * getBayerSample(pos - stp) + getBayerSample(pos - 2*stp) + getBayerSample(pos + 2*stp))/6.0);
}

float dt(ivec2 pos, ivec2 stp) {
    float c = dxy(pos, stp);
    float c2 = dxy(pos + 2*stp, stp);
    float c1 = dxy(pos + stp, stp);
    return (abs(c - c1) + abs(c1 - c2))/2.0;
}

float dxy2(ivec2 pos, ivec2 stp) {
    float c = getBayerSample(pos);
    /*if (direction == 0){
        stp = ivec2(1,0);
    } else {
        stp = ivec2(0,1);
    }*/
    return (abs(getBayerSample(pos) - getBayerSample(pos + 2*stp)) + abs(getBayerSample(pos - stp) - getBayerSample(pos + stp))) / 2.0 + alpha * dt(pos,stp);
}

float IG(ivec2 pos, int stp) {
    //int pattern = getBayerPattern(pos);
    //float useGreen = (pattern == 1 || pattern == 2) ? -1.0 : 1.0;
    //float useInv = 1.0 - useGreen;
    /*if (direction == 0) {
        return 2.0 * dxy2(pos,0) + dxy2(pos + ivec2(0,-1),0) + dxy2(pos + ivec2(0,1),0);
    } else {
        return 2.0 * dxy2(pos,1) + dxy2(pos + ivec2(-1,0),1) + dxy2(pos + ivec2(1,0),1);
    }*/
    return imageLoad(igTexture, pos)[stp];
    //ivec2 invStep = ivec2(1,1)-stp;
    //return 2.0 * dxy2(pos,stp) + dxy2(pos - invStep,stp) + dxy2(pos + invStep,stp);
}

float dl(ivec2 pos){
    return max(getBayerSample(pos),EPS2) / max(gr(pos).x,EPS2);
    //return max(gr(pos).x,EPS2)-max(getBayerSample(pos),EPS2);
}

float estimateD(ivec2 pos){
    float igE = IG(pos + ivec2(0, 0), 0);
    float igW = IG(pos + ivec2(-2, 0), 0);
    float igS = IG(pos + ivec2(0, 0), 1);
    float igN = IG(pos + ivec2(0, -2), 1);

    float wE = 1.0 / (igE + EPS);
    float wS = 1.0 / (igS + EPS);
    float wW = 1.0 / (igW + EPS);
    float wN = 1.0 / (igN + EPS);
    float dh = igE + igW + EPS2;
    float dv = igN + igS + EPS2;
    float E = max(dh/dv, dv/dh);
    float res0 = (wE * dl(pos + ivec2(2, 0)) + wW * dl(pos + ivec2(-2, 0)) + wS * dl(pos + ivec2(0, 2)) + wN * dl(pos + ivec2(0, -2)))/(wE + wW + wS + wN);
    //return res;
    //return dl(pos);

    /*if (dh > dv){
        //return (wS * dl(pos + ivec2(0, 2)) + wN * dl(pos + ivec2(0, -2)) + (wS + wN)*dl(pos))/(2.0*wS + 2.0*wN);
        return (wS * dl(pos + ivec2(0, 2)) + wN * dl(pos + ivec2(0, -2)))/(wS + wN);
    } else {
        //return (wE * dl(pos + ivec2(2, 0)) + wW * dl(pos + ivec2(-2, 0)) + (wE + wW)*dl(pos))/(2.0*wE + 2.0*wW);
        return (wE * dl(pos + ivec2(2, 0)) + wW * dl(pos + ivec2(-2, 0)))/(wE + wW);
    }*/
    float c = dl(pos);
    float v0 = dl(pos + ivec2(2,0));
    float v1 = dl(pos + ivec2(-2,0));
    float v2 = dl(pos + ivec2(0,2));
    float v3 = dl(pos + ivec2(0,-2));
    float w0 = 1.0-abs(v0 - c);
    float w1 = 1.0-abs(v1 - c);
    float w2 = 1.0-abs(v2 - c);
    float w3 = 1.0-abs(v3 - c);
    float w4 = 1.0-abs(res0 - c);
    //float wm = min(min(min(min(w0,w1),w2),w3),w4)*0.999;
    float wm = min(min(min(w0,w1),w2),w3)*0.999;
    w0 -= wm;
    w1 -= wm;
    w2 -= wm;
    w3 -= wm;
    w4 -= wm;
    //float samp = getBayerSample(pos);
    //float noise = sqrt(samp*NOISES + NOISEO);
    float res = (w0*v0 + w1*v1 + w2*v2 + w3*v3)/(w0+w1+w2+w3);
    //if (E > THRESHOLD){
        //return (wE * dl(pos + ivec2(2, 0)) + wW * dl(pos + ivec2(-2, 0)) + wS * dl(pos + ivec2(0, 2)) + wN * dl(pos + ivec2(0, -2)))/(wE + wW + wS + wN);
        //    return mix(dl(pos), res, BETA);
    //    return dl(pos);
    //}
    //return mix(res,dl(pos), clamp((E-1.0)*1.0,0.0,1.0));
    return res;

    //return res;
    //return dl(pos);
    /*float dir = float(gr(pos).y);
    float dte = 0.0;
    if (dir < 0.5){
        return dl(pos);
    } else {
        if (dir > 0.5){
            return (wE * dl(pos + ivec2(2, 0)) + wW * dl(pos + ivec2(-2, 0)))/(wE + wW);
        } else {
            return (wS * dl(pos + ivec2(0, 2)) + wN * dl(pos + ivec2(0, -2)))/(wS + wN);
        }
    }*/
    /*float cin = dl(pos);
    float cc1 = dl(pos + ivec2(2, 0));
    float cc2 = dl(pos + ivec2(-2, 0));
    float cc3 = dl(pos + ivec2(0, 2));
    float cc4 = dl(pos + ivec2(0, -2));
    float sigY = 0.1;
    float d1 = (abs(cc1-cin));
    float d2 = (abs(cc2-cin));
    float d3 = (abs(cc3-cin));
    float d4 = (abs(cc4-cin));
    float w1 = (1.0-d1*d1/(d1*d1 + sigY));
    float w2 = (1.0-d2*d2/(d2*d2 + sigY));
    float w3 = (1.0-d3*d3/(d3*d3 + sigY));
    float w4 = (1.0-d4*d4/(d4*d4 + sigY));
    float wm = min(min(min(w1,w2),w3),w4)*0.99;
    w1 -= wm;
    w2 -= wm;
    w3 -= wm;
    w4 -= wm;*/

    //return (wE * w1*cc1 + wW * w2*cc2 + wS * w3*cc3 + wN * w4*cc4)/(wE * w1+wW * w2+wS * w3+ wN * w4);


    //return (wE * dl(pos + ivec2(2, 0)) + wW * dl(pos + ivec2(-2, 0)) + wS * dl(pos + ivec2(0, 2)) + wN * dl(pos + ivec2(0, -2)))/(wE + wW + wS + wN);
    //float dir = float(gr(pos).y);
    //float dir = 0.0;
    /*float dte = 0.0;
    if (dir < 0.5){
        dte = (wE * dl(pos + ivec2(0, 2)) + wW * dl(pos + ivec2(0, -2)))/(wE + wW);
    } else {
        if (dir > 0.5){
            dte = (wS * dl(pos + ivec2(2, 0)) + wN * dl(pos + ivec2(-2, 0)))/(wS + wN);
        } else {
            dte = (wE * dl(pos + ivec2(0, 2)) + wW * dl(pos + ivec2(0, -2)) + wS * dl(pos + ivec2(2, 0)) + wN * dl(pos + ivec2(-2, 0)))/(wE + wW + wS + wN);
        }
    }*/
    return 0.0;
}

float dtcv(ivec2 pos){
    return estimateD(pos);
    //return mix(estimateD(pos), dl(pos), BETA);
    //float diff = (dl(pos) - estimateD(pos));
    //return mix(dl(pos), estimateD(pos), exp(-diff*diff*350.0));
}

float dhtd(ivec2 pos){
    float igE = IG(pos, 0);
    float igW = IG(pos + ivec2(-2, 0), 0);
    float igS = IG(pos, 1);
    float igN = IG(pos + ivec2(0, -2), 1);

    float wE = 1.0 / (igE + EPS);
    float wS = 1.0 / (igS + EPS);
    float wW = 1.0 / (igW + EPS);
    float wN = 1.0 / (igN + EPS);

    float wNW = 1.0 / (igN + igW + EPS);
    float wNE = 1.0 / (igN + igE + EPS);
    float wSE = 1.0 / (igS + igE + EPS);
    float wSW = 1.0 / (igS + igW + EPS);

    return (wNW*dtcv(pos+ivec2(-1,-1))+wNE*dtcv(pos+ivec2(1,-1))+wSE*dtcv(pos+ivec2(1,1))+wSW*dtcv(pos+ivec2(-1,1)))/(wNW + wNE + wSE + wSW);
}

float dhtg0(ivec2 pos){
    float igE = IG(pos, 0);
    float igW = IG(pos + ivec2(-2, 0), 0);
    float igS = IG(pos, 1);
    float igN = IG(pos + ivec2(0, -2), 1);

    float wE = 1.0 / (igE + EPS);
    float wS = 1.0 / (igS + EPS);
    float wW = 1.0 / (igW + EPS);
    float wN = 1.0 / (igN + EPS);

    ivec2 stp = ivec2(1,0);
    ivec2 invStp = ivec2(0,1);

    return (wE*dhtd(pos+stp)+wW*dhtd(pos-stp)+wS*dtcv(pos+invStp)+wN*dtcv(pos-invStp))/(wE + wW + wS + wN);
}

float dhtg1(ivec2 pos){
    float igE = IG(pos, 0);
    float igW = IG(pos + ivec2(-2, 0), 0);
    float igS = IG(pos, 1);
    float igN = IG(pos + ivec2(0, -2), 1);

    float wE = 1.0 / (igE + EPS);
    float wS = 1.0 / (igS + EPS);
    float wW = 1.0 / (igW + EPS);
    float wN = 1.0 / (igN + EPS);

    ivec2 stp = ivec2(1,0);
    ivec2 invStp = ivec2(0,1);

    return (wE*dtcv(pos+stp)+wW*dtcv(pos-stp)+wS*dhtd(pos+invStp)+wN*dhtd(pos-invStp))/(wE + wW + wS + wN);
}

void main() {
    //ivec2 pos = ivec2(gl_FragCoord.xy);
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    int fact1 = pos.x%2;
    int fact2 = pos.y%2;
    float dtc = dtcv(pos);
    vec3 outp;
    float igE = IG(pos, 0);
    float igW = IG(pos + ivec2(-2, 0), 0);
    float igS = IG(pos, 1);
    float igN = IG(pos + ivec2(0, -2), 1);

    float wE = 1.0 / (igE + EPS);
    float wS = 1.0 / (igS + EPS);
    float wW = 1.0 / (igW + EPS);
    float wN = 1.0 / (igN + EPS);
    bool skip = false;
    float minCol = 1.0;
    float maxCol = 0.0;
    for(int i = -1; i <= 1; i++){
        for(int j = -1; j <= 1; j++){
            minCol = min(minCol, getBayerSample(pos + ivec2(i,j)));
            maxCol = max(maxCol, getBayerSample(pos + ivec2(i,j)));
        }
    }
    if(minCol < greenmin || maxCol > greenmax){
        skip = true;
    }

    if(fact1 ==0 && fact2 == 0) {//rggb
        //outp.g = gr(pos).x;
        outp.r = getBayerSample(pos);
        //outp.g = max(getBayerSample(pos),EPS2) / dtc;
        //outp.g = getBayerSample(pos) / dtc;
        //float grk = max(outp.g,EPS2);
        float grk = gr(pos).x;
        //outp.b = interpolateColor(pos);
        //outp.b = grk * (wNW*dtcv(pos+ivec2(-1,-1))+wNE*dtcv(pos+ivec2(1,-1))+wSE*dtcv(pos+ivec2(1,1))+wSW*dtcv(pos+ivec2(-1,1)))/(wNW + wNE + wSE + wSW);
        //outp.b = grk * dhtd(pos);
        if (skip) {
            outp.g = grk;
            outp.b = (getBayerSample(pos+ivec2(1,1)) + getBayerSample(pos+ivec2(-1,-1)) + getBayerSample(pos+ivec2(1,-1)) + getBayerSample(pos+ivec2(-1,1)))/4.0;
        } else {
            outp.g = getBayerSample(pos) / dtc;
            outp.b = outp.g * dhtd(pos);
        }
    } else
    if(fact1 ==1 && fact2 == 0) {//grbg
        outp.g = gr(pos).x;
        //outp.g = max(getBayerSample(pos),EPS2) / dtc;
        //float grk = max(outp.g,EPS2);
        float grk = outp.g;
        //outp.r = grk * (dtcv(pos+ivec2(1,0))+dtcv(pos+ivec2(-1,0)))/2.0;
        //outp.r = grk *  dhtg1(pos);
        //outp.b = grk * (wE*dhtd(pos+ivec2(1,0))+wW*dhtd(pos+ivec2(-1,0))+wS*dtcv(pos+ivec2(0,1))+wN*dtcv(pos+ivec2(0,-1)))/(wE + wW + wS + wN);
        //outp.b = grk * (dtcv(pos+ivec2(0,1))+dtcv(pos+ivec2(0,-1)))/2.0;
        //outp.b = grk * dhtg0(pos);
        if (skip){
            outp.r = (getBayerSample(pos+ivec2(1,0)) + getBayerSample(pos+ivec2(-1,0)))/2.0;
            outp.b = (getBayerSample(pos+ivec2(0,1)) + getBayerSample(pos+ivec2(0,-1)))/2.0;
        } else {
            outp.b = grk * dhtg0(pos);
            outp.r = grk * dhtg1(pos);
        }
        //outp.r = grk * (wE*dtcv(pos+ivec2(1,0))+wW*dtcv(pos+ivec2(-1,0))+wS*dhtd(pos+ivec2(0,1))+wN*dhtd(pos+ivec2(0,-1)))/(wE + wW + wS + wN);
    } else
    if(fact1 ==0 && fact2 == 1) {//gbrg
        outp.g = gr(pos).x;
        //outp.g = max(getBayerSample(pos),EPS2) / dtc;
        //float grk = max(outp.g,EPS2);
        float grk = outp.g;
        //outp.b = grk * (dtcv(pos+ivec2(1,0))+dtcv(pos+ivec2(-1,0)))/2.0;
        //outp.b = grk *  dhtg1(pos);
        //outp.r = grk * (wE*dhtd(pos+ivec2(1,0))+wW*dhtd(pos+ivec2(-1,0))+wS*dtcv(pos+ivec2(0,1))+wN*dtcv(pos+ivec2(0,-1)))/(wE + wW + wS + wN);
        //outp.r = grk * (dtcv(pos+ivec2(0,1))+dtcv(pos+ivec2(0,-1)))/2.0;
        //outp.r = grk *  dhtg0(pos);
        //outp.b = grk * (wE*dtcv(pos+ivec2(1,0))+wW*dtcv(pos+ivec2(-1,0))+wS*dhtd(pos+ivec2(0,1))+wN*dhtd(pos+ivec2(0,-1)))/(wE + wW + wS + wN);
        //outp.b = grk * (wE*dhtd(pos+ivec2(1,0))+wW*dhtd(pos+ivec2(-1,0))+wS*dtcv(pos+ivec2(0,1))+wN*dtcv(pos+ivec2(0,-1)))/(wE + wW + wS + wN);
        //outp.b = grk * (dtcv(pos+ivec2(0,1))+dtcv(pos+ivec2(0,-1)))/2.0;
        //outp.b = grk * dhtg0(pos);
        if (skip){
            outp.b = (getBayerSample(pos+ivec2(1,0)) + getBayerSample(pos+ivec2(-1,0)))/2.0;
            outp.r = (getBayerSample(pos+ivec2(0,1)) + getBayerSample(pos+ivec2(0,-1)))/2.0;
        } else {
            outp.r = grk * dhtg0(pos);
            outp.b = grk * dhtg1(pos);
        }
    } else  {//bggr
        //outp.g = gr(pos).x;
        outp.b = getBayerSample(pos);
        //outp.g = max(getBayerSample(pos),EPS2) / dtc;
        //outp.g = getBayerSample(pos) / dtc;
        //float grk = max(outp.g,EPS2);
        float grk = gr(pos).x;
        //outp.r = interpolateColor(pos);
        //outp.r = grk * (wNW*dtcv(pos+ivec2(-1,-1))+wNE*dtcv(pos+ivec2(1,-1))+wSE*dtcv(pos+ivec2(1,1))+wSW*dtcv(pos+ivec2(-1,1)))/(wNW + wNE + wSE + wSW);
        if (skip){
            outp.g = grk;
            outp.r = (getBayerSample(pos+ivec2(1,1)) + getBayerSample(pos+ivec2(-1,-1)) + getBayerSample(pos+ivec2(1,-1)) + getBayerSample(pos+ivec2(-1,1)))/4.0;
        } else {
            outp.g = getBayerSample(pos) / dtc;
            outp.r = outp.g * dhtd(pos);
        }
        //outp.r = grk * dhtd(pos);
    }
    //outp.rb = vec2(outp.g);
    //outp.rg = vec2((igE+igW)/2.0, (igS+igN)/2.0);
    //outp.b = 0.0;
    outp = clamp(outp,0.0,1.0);
    //outp.rb = vec2(igE, igS);
    imageStore(outTexture, pos, vec4(outp, 1.0));
}