#version 330

uniform sampler2D MainSampler;    // current running average
uniform sampler2D DisplaySampler; // last completed N-frame average

layout(std140) uniform DisplayLerpUniforms {
    float displayWeight;
    int   padding0;
    int   padding1;
    int   padding2;
};

in vec2 texCoord;
layout(location = 0) out vec4 color;

void main() {
    vec3 running  = texture(MainSampler,    texCoord).rgb;
    vec3 display  = texture(DisplaySampler, texCoord).rgb;
    color = vec4(mix(display, running, displayWeight), 1.0);
}