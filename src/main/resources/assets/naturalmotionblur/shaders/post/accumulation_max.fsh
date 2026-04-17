#version 330

uniform sampler2D MainSampler;
uniform sampler2D PrevSampler;

layout(std140) uniform AccumulationUniforms {
    float blendFactor;
    int   padding0;
    int   padding1;
    int   padding2;
};

in vec2 texCoord;
in vec2 oneTexel;
uniform vec2 InSize;
out vec4 fragColor;

void main() {
    vec4 CurrTexel = texture(MainSampler, texCoord);
    vec4 PrevTexel = texture(PrevSampler, texCoord);
    vec4 fadedPrev = PrevTexel * blendFactor;
    fragColor = max(CurrTexel, fadedPrev);
    fragColor.w = 1.0;
}