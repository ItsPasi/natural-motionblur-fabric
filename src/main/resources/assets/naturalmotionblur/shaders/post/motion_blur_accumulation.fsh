#version 330

uniform sampler2D MainSampler;
uniform sampler2D AccumulationSampler;

layout(std140) uniform FrameAccumulationUniforms {
    float blendWeight;
    int   padding0;
    int   padding1;
    int   padding2;
};

in vec2 texCoord;
layout(location = 0) out vec4 color;

void main() {
    vec3 currentFrame = texture(MainSampler, texCoord).rgb;
    vec3 accumulated  = texture(AccumulationSampler, texCoord).rgb;
    vec3 result = sqrt(mix(accumulated * accumulated,
                           currentFrame * currentFrame,
                           blendWeight));

    color = vec4(result, 1.0);
}