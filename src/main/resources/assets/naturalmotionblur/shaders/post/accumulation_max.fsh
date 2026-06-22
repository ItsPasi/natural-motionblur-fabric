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
layout(location = 0) out vec4 fragColor;

void main() {
    vec3 curr = texture(MainSampler, texCoord).rgb;
    vec3 prev = texture(PrevSampler, texCoord).rgb;

    vec3 fadedPrev = prev * blendFactor;
    fragColor = vec4(max(curr, fadedPrev), 1.0);
}