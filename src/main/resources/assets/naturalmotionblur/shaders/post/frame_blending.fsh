#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sample0Sampler;
uniform sampler2D Sample1Sampler;
uniform sampler2D Sample2Sampler;
uniform sampler2D Sample3Sampler;
uniform sampler2D Sample4Sampler;
uniform sampler2D Sample5Sampler;
uniform sampler2D Sample6Sampler;
uniform sampler2D Sample7Sampler;
uniform sampler2D Sample8Sampler;
uniform sampler2D Sample9Sampler;
uniform sampler2D Sample10Sampler;
uniform sampler2D Sample11Sampler;
uniform sampler2D Sample12Sampler;
uniform sampler2D Sample13Sampler;
uniform sampler2D Sample14Sampler;
uniform sampler2D Sample15Sampler;
uniform sampler2D Sample16Sampler;
uniform sampler2D Sample17Sampler;
uniform sampler2D Sample18Sampler;
uniform sampler2D Sample19Sampler;
uniform sampler2D Sample20Sampler;
uniform sampler2D Sample21Sampler;
uniform sampler2D Sample22Sampler;
uniform sampler2D Sample23Sampler;

layout(std140) uniform FrameBlendParamsUniforms {
    float invTotalWeight;
    int   activeCount;
    float Sample0Weight;
    float Sample1Weight;
    float Sample2Weight;
    float Sample3Weight;
    float Sample4Weight;
    float Sample5Weight;
    float Sample6Weight;
    float Sample7Weight;
    float Sample8Weight;
    float Sample9Weight;
    float Sample10Weight;
    float Sample11Weight;
    float Sample12Weight;
    float Sample13Weight;
    float Sample14Weight;
    float Sample15Weight;
    float Sample16Weight;
    float Sample17Weight;
    float Sample18Weight;
    float Sample19Weight;
    float Sample20Weight;
    float Sample21Weight;
    float Sample22Weight;
    float Sample23Weight;
    float padding0;
    float padding1;
};

layout(location = 0) in vec2 texCoord;
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
        case 8: return srgbToLinear(texture(Sample8Sampler, texCoord).rgb);
        case 9: return srgbToLinear(texture(Sample9Sampler, texCoord).rgb);
        case 10: return srgbToLinear(texture(Sample10Sampler, texCoord).rgb);
        case 11: return srgbToLinear(texture(Sample11Sampler, texCoord).rgb);
        case 12: return srgbToLinear(texture(Sample12Sampler, texCoord).rgb);
        case 13: return srgbToLinear(texture(Sample13Sampler, texCoord).rgb);
        case 14: return srgbToLinear(texture(Sample14Sampler, texCoord).rgb);
        case 15: return srgbToLinear(texture(Sample15Sampler, texCoord).rgb);
        case 16: return srgbToLinear(texture(Sample16Sampler, texCoord).rgb);
        case 17: return srgbToLinear(texture(Sample17Sampler, texCoord).rgb);
        case 18: return srgbToLinear(texture(Sample18Sampler, texCoord).rgb);
        case 19: return srgbToLinear(texture(Sample19Sampler, texCoord).rgb);
        case 20: return srgbToLinear(texture(Sample20Sampler, texCoord).rgb);
        case 21: return srgbToLinear(texture(Sample21Sampler, texCoord).rgb);
        case 22: return srgbToLinear(texture(Sample22Sampler, texCoord).rgb);
        case 23: return srgbToLinear(texture(Sample23Sampler, texCoord).rgb);
        default: return vec3(0.0);
    }
}

float loadSampleWeight(int index) {
    switch (index) {
        case 0: return Sample0Weight;
        case 1: return Sample1Weight;
        case 2: return Sample2Weight;
        case 3: return Sample3Weight;
        case 4: return Sample4Weight;
        case 5: return Sample5Weight;
        case 6: return Sample6Weight;
        case 7: return Sample7Weight;
        case 8: return Sample8Weight;
        case 9: return Sample9Weight;
        case 10: return Sample10Weight;
        case 11: return Sample11Weight;
        case 12: return Sample12Weight;
        case 13: return Sample13Weight;
        case 14: return Sample14Weight;
        case 15: return Sample15Weight;
        case 16: return Sample16Weight;
        case 17: return Sample17Weight;
        case 18: return Sample18Weight;
        case 19: return Sample19Weight;
        case 20: return Sample20Weight;
        case 21: return Sample21Weight;
        case 22: return Sample22Weight;
        case 23: return Sample23Weight;
        default: return 0.0;
    }
}

void main() {
    vec3 accumLinear = vec3(0.0);
    for (int i = 0; i < 24; i++) {
        if (i >= activeCount) break;
        float weight = loadSampleWeight(i);
        accumLinear += loadSampleLinear(i) * weight;
    }
    color = vec4(linearToSrgb(accumLinear * invTotalWeight), 1.0);
}