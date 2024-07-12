precision highp float;
precision highp sampler2D;
uniform sampler2D RawBuffer;
uniform sampler2D GreenBuffer;
uniform int yOffset;
out vec3 Output;


#define alpha 3.75
#define BETA 0.42
#define THRESHOLD 1.9
#define L 3
//#define greenmin (0.04)
#define greenmin (0.01)
//#define greenmax (0.9)
#define greenmax (0.99)
#import interpolation

int getBayerPattern(ivec2 pos) {
    int x = pos.x % 2;
    int y = pos.y % 2;
    if (x == 0 && y == 0) return 0; // R G
    if (x == 1 && y == 0) return 1; // G R
    if (x == 0 && y == 1) return 2; // G B
    return 3; // B G
}

float getBayerSample(ivec2 pos) {
    return float(texelFetch(RawBuffer, pos, 0).x);
}

float gr(ivec2 pos){
    return float(texelFetch(GreenBuffer, pos, 0).x);
}

float bayer(ivec2 pos){
    return float(texelFetch(RawBuffer, pos, 0).x);
}

float dgr(ivec2 pos){
    return gr(pos) - bayer(pos);
}


float dxy(ivec2 pos, int direction) {
    int pattern = getBayerPattern(pos);
    float useGreen = (pattern == 1 || pattern == 2) ? 1.0 : -1.0;
    if (direction == 0) {
        return abs((4.0 * getBayerSample(pos) - 3.0 * getBayerSample(pos + ivec2(1,0)) - 3.0 * getBayerSample(pos + ivec2(-1,0)) + getBayerSample(pos + ivec2(-2,0)) + getBayerSample(pos + ivec2(2,0)))/6.0);
        //return (2.0 * getBayerSample(pos) - getBayerSample(pos + ivec2(1,0)) - getBayerSample(pos + ivec2(-1,0)))/2.0;
    } else {
        return abs((4.0 * getBayerSample(pos) - 3.0 * getBayerSample(pos + ivec2(0,1)) - 3.0 * getBayerSample(pos + ivec2(0,-1)) + getBayerSample(pos + ivec2(0,-2)) + getBayerSample(pos + ivec2(0,2)))/6.0);
        //return (2.0 * getBayerSample(pos) - getBayerSample(pos + ivec2(0,1)) - getBayerSample(pos + ivec2(0,-1)))/2.0;
    }
}

float dt(ivec2 pos, int direction) {
    float c = dxy(pos, direction);
    if (direction == 0) {
        float c2 = dxy(pos + ivec2(2, 0), direction);
        float c1 = dxy(pos + ivec2(1, 0), direction);
        return (abs(c - c1) + abs(c1 - c2))/2.0;
    } else {
        float c2 = dxy(pos + ivec2(0, 2), direction);
        float c1 = dxy(pos + ivec2(0, 1), direction);
        return (abs(c - c1) + abs(c1 - c2))/2.0;
    }
}

float dxy2(ivec2 pos, int direction) {
    float c = getBayerSample(pos);
    float c1;
    float c2;
    float c3;
    if (direction == 0){
        c1 = getBayerSample(pos + ivec2(2,0));
        c2 = getBayerSample(pos + ivec2(-1,0));
        c3 = getBayerSample(pos + ivec2(1,0));
    } else {
        c1 = getBayerSample(pos + ivec2(0,2));
        c2 = getBayerSample(pos + ivec2(0,-1));
        c3 = getBayerSample(pos + ivec2(0,1));
    }
    return (abs(c - c1) + abs(c2 - c3)) / 2.0 + alpha * dt(pos,direction);
}

float IG(ivec2 pos, int direction) {
    int pattern = getBayerPattern(pos);
    float useGreen = (pattern == 1 || pattern == 2) ? -1.0 : 1.0;
    float useInv = 1.0 - useGreen;
    if (direction == 0) {
        return 2.0 * dxy2(pos,0) + dxy2(pos + ivec2(0,-1),0) + dxy2(pos + ivec2(0,1),0);
    } else {
        return 2.0 * dxy2(pos,1) + dxy2(pos + ivec2(-1,0),1) + dxy2(pos + ivec2(1,0),1);
    }
}


float interpolateColor(in ivec2 coords){
    bool usegreen = true;
    float green[5];
    green[0] = float(texelFetch(GreenBuffer, (coords+ivec2(-1,-1)), 0).x);
    green[1] = float(texelFetch(GreenBuffer, (coords+ivec2(1,-1)),  0).x);
    green[2] = float(texelFetch(GreenBuffer, (coords+ivec2(-1,1)),  0).x);
    green[3] = float(texelFetch(GreenBuffer, (coords+ivec2(1,1)),   0).x);
    green[4] = float(texelFetch(GreenBuffer, (coords),              0).x);
    for(int i = 0; i<5; i++)if(green[i] < greenmin || green[i] > greenmax) usegreen = false;
    if(usegreen){
        float coeff[4];
        coeff[0] = float(texelFetch(RawBuffer, (coords+ivec2(-1,-1)), 0).x)/(green[0]);
        coeff[1] = float(texelFetch(RawBuffer, (coords+ivec2(1,-1)),  0).x)/(green[1]);
        coeff[2] = float(texelFetch(RawBuffer, (coords+ivec2(-1,1)),  0).x)/(green[2]);
        coeff[3] = float(texelFetch(RawBuffer, (coords+ivec2(1,1)),   0).x)/(green[3]);
        return (green[4]*(coeff[0]+coeff[1]+coeff[2]+coeff[3])/4.);
    } else {
        return ((float(texelFetch(RawBuffer, (coords+ivec2(-1,-1)), 0).x)+float(texelFetch(RawBuffer, (coords+ivec2(1,-1)), 0).x)
        +float(texelFetch(RawBuffer, (coords+ivec2(-1,1)), 0).x)+float(texelFetch(RawBuffer, (coords+ivec2(1,1)), 0).x))/(4.));
        }
}
float interpolateColorx(in ivec2 coords){
    bool usegreen = true;
    float green[3];
    green[0] = float(texelFetch(GreenBuffer, (coords+ivec2(-1,0)), 0).x);
    green[1] = float(texelFetch(GreenBuffer, (coords+ivec2(0,0)),  0).x);
    green[2] = float(texelFetch(GreenBuffer, (coords+ivec2(1,0)),  0).x);
    for(int i = 0; i<3; i++)if(green[i] < greenmin || green[i] > greenmax) usegreen = false;
    if(usegreen){
        float coeff[2];
        coeff[0] = float(texelFetch(RawBuffer, (coords+ivec2(-1,0)), 0).x)/(green[0]);
        coeff[1] = float(texelFetch(RawBuffer, (coords+ivec2(1,0)),  0).x)/(green[2]);
        return (green[1]*(coeff[0]+coeff[1])/2.);
    } else {
        return ((float(texelFetch(RawBuffer, (coords+ivec2(-1,0)), 0).x)+float(texelFetch(RawBuffer, (coords+ivec2(1,0)), 0).x))/(2.));
    }
}
float interpolateColory(in ivec2 coords){
    bool usegreen = true;
    float green[3];
    green[0] = float(texelFetch(GreenBuffer, (coords+ivec2(0,-1)), 0).x);
    green[1] = float(texelFetch(GreenBuffer, (coords+ivec2(0,0)),  0).x);
    green[2] = float(texelFetch(GreenBuffer, (coords+ivec2(0,1)),  0).x);
    for(int i = 0; i<3; i++)if(green[i] < greenmin || green[i] > greenmax) usegreen = false;
    if(usegreen){
        float coeff[2];
        coeff[0] = float(texelFetch(RawBuffer, (coords+ivec2(0,-1)), 0).x)/(green[0]);
        coeff[1] = float(texelFetch(RawBuffer, (coords+ivec2(0,1)),  0).x)/(green[2]);
        return (green[1]*(coeff[0]+coeff[1])/2.);
    } else {
        return ((float(texelFetch(RawBuffer, (coords+ivec2(0,-1)), 0).x)+float(texelFetch(RawBuffer, (coords+ivec2(0,1)), 0).x))/(2.));
    }
}

float dl(ivec2 pos){
    return getBayerSample(pos) - gr(pos);
}

float estimateD(ivec2 pos){
    float igE = IG(pos, 1);
    float igS = IG(pos, 0);
    float igW = IG(pos + ivec2(-2, 0), 1);
    float igN = IG(pos + ivec2(0, -2), 0);

    float wE = 1.0 / (igE + 0.0001);
    float wS = 1.0 / (igS + 0.0001);
    float wW = 1.0 / (igW + 0.0001);
    float wN = 1.0 / (igN + 0.0001);

    float dir = float(texelFetch(GreenBuffer, pos, 0).y);
    float dte = 0.0;
    if (dir < 0.5){
        dte = (wE * dl(pos + ivec2(0, 2)) + wW * dl(pos + ivec2(0, -2)))/(wE + wW);
    } else {
        if (dir > 0.5){
            dte = (wS * dl(pos + ivec2(2, 0)) + wN * dl(pos + ivec2(-2, 0)))/(wS + wN);
        } else {
            dte = (wE * dl(pos + ivec2(0, 2)) + wW * dl(pos + ivec2(0, -2)) + wS * dl(pos + ivec2(2, 0)) + wN * dl(pos + ivec2(-2, 0)))/(wE + wW + wS + wN);
        }
    }
    return dte;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    int fact1 = xy.x%2;
    int fact2 = xy.y%2;
    xy+=ivec2(0,yOffset);
    //float dtc = mix(estimateD(xy), dl(xy), BETA);
    if(fact1 ==0 && fact2 == 0) {//rggb
        Output.g = texelFetch(GreenBuffer, (xy), 0).x;
        Output.r = float(texelFetch(RawBuffer, (xy), 0).x);
        Output.b = interpolateColor(xy);
        //Output.g = getBayerSample(xy) - dtc;
        //Output.b = gr(xy) + dtc;
    } else
    if(fact1 ==1 && fact2 == 0) {//grbg
        Output.g = texelFetch(GreenBuffer, (xy), 0).x;
        Output.r = interpolateColorx(xy);
        Output.b = interpolateColory(xy);
    } else
    if(fact1 ==0 && fact2 == 1) {//gbrg
        Output.g = texelFetch(GreenBuffer, (xy), 0).x;
        Output.b = interpolateColorx(xy);
        Output.r = interpolateColory(xy);

    } else  {//bggr
        Output.g = texelFetch(GreenBuffer, (xy), 0).x;
        Output.b = float(texelFetch(RawBuffer, (xy), 0).x);
        Output.r = interpolateColor(xy);
        //Output.g = getBayerSample(xy) - dtc;
    }
    Output = clamp(Output,0.0,1.0);
}