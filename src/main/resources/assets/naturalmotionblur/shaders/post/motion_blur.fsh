#version 330 core

uniform sampler2D MainSampler;
uniform sampler2D MainDepthSampler;
uniform float BlendFactor;
uniform vec3 cameraPos;
uniform vec3 prevCameraPos;
uniform vec2 view_res;
uniform mat4 mvInverse;
uniform mat4 projInverse;
uniform mat4 prevModelView;
uniform mat4 prevProjection;
uniform int blurAlgorithm;
uniform int motionBlurSamples;
in vec2 texCoord;
layout(location = 0) out vec4 color;

#define rcp(x) (1.0 / (x))

vec3 reproject(vec3 screen_pos) {
    vec3 ndc = screen_pos * 2.0 - 1.0;
    vec4 view_pos4 = projInverse * vec4(ndc, 1.0);
    vec3 view_pos = view_pos4.xyz / view_pos4.w;

    vec3 world_pos = (mvInverse * vec4(view_pos, 1.0)).xyz + (cameraPos - prevCameraPos);
    vec4 prev_proj = prevProjection * (prevModelView * vec4(world_pos, 1.0));

    return (prev_proj.xyz / prev_proj.w) * 0.5 + 0.5;
}

vec2 clampLength(vec2 velocity) {
    float lenSq = dot(velocity, velocity);
    return (lenSq > 0.16) ? velocity * (0.4 * inversesqrt(lenSq)) : velocity;
}

float noise(vec2 pos) {
    return fract(52.9829189 * fract(0.06711056 * pos.x + 0.00583715 * pos.y));
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);

    float depth = texelFetch(MainDepthSampler, texel, 0).x;
    vec2 velocity = texCoord - reproject(vec3(texCoord, depth)).xy;
    velocity = clampLength(velocity);

    float speed = length(velocity);
    int dynamicSamples = int(ceil(speed * float(motionBlurSamples)));
    dynamicSamples = clamp(dynamicSamples, 1, motionBlurSamples);
    if (dynamicSamples % 2 != 0) {
        dynamicSamples += 1;
    }
    int halfSamples = dynamicSamples / 2;

    vec2 totalOffset = BlendFactor * velocity;
    vec2 baseStep = totalOffset / float(dynamicSamples);

    vec3 color_sum = vec3(0.0);
    vec2 seed = texCoord * view_res;

    bool centerBlur = blurAlgorithm != 0;
    for (int i = 0; i < dynamicSamples; ++i) {
        float fi = float(i);

        float jitter = noise(seed + vec2(fi, fi * 1.4));
        float sample_offset = fi - halfSamples + jitter;

        vec2 pos = texCoord + sample_offset * baseStep;
        vec3 color = texture(MainSampler, pos).rgb;

        color_sum += color * color;
    }
    color = vec4(sqrt(color_sum / float(dynamicSamples)), 1.0);
}