#version 330

uniform sampler2D Sample0Sampler;
uniform sampler2D Sample1Sampler;
uniform sampler2D Sample2Sampler;
uniform sampler2D Sample3Sampler;
uniform sampler2D Sample4Sampler;
uniform sampler2D Sample5Sampler;
uniform sampler2D Sample6Sampler;
uniform sampler2D Sample7Sampler;

layout(std140) uniform FrameBlendParamsUniforms {
    float invSampleCount;
    int   activeCount;
    int   padding1;
    int   padding2;
};

in vec2 texCoord;
layout(location = 0) out vec4 color;

vec3 srgbToLinear(vec3 c) {
    return c * c;
}

vec3 linearToSrgb(vec3 c) {
    return sqrt(max(c, vec3(0.0)));
}

vec3 loadSampleLinear(int index) {
    switch (index) {
        case 0: return srgbToLinear(texture(Sample0Sampler, texCoord).rgb);
        case 1: return srgbToLinear(texture(Sample1Sampler, texCoord).rgb);
        case 2: return srgbToLinear(texture(Sample2Sampler, texCoord).rgb);
        case 3: return srgbToLinear(texture(Sample3Sampler, texCoord).rgb);
        case 4: return srgbToLinear(texture(Sample4Sampler, texCoord).rgb);
        case 5: return srgbToLinear(texture(Sample5Sampler, texCoord).rgb);
        case 6: return srgbToLinear(texture(Sample6Sampler, texCoord).rgb);
        case 7: return srgbToLinear(texture(Sample7Sampler, texCoord).rgb);
        default: return vec3(0.0);
    }
}

void main() {
    vec3 accumLinear = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        if (i >= activeCount) break;
        accumLinear += loadSampleLinear(i);
    }
    color = vec4(linearToSrgb(accumLinear * invSampleCount), 1.0);
}
