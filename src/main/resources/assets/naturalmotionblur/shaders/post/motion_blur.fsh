#version 330 core

uniform sampler2D MainSampler;
uniform sampler2D MainDepthSampler;
uniform float BlendFactor;
uniform vec2 view_res;
uniform mat4 mvInverse;
uniform mat4 projInverse;
uniform mat4 prevModelView;
uniform mat4 prevProjection;
uniform mat4 projection;
uniform vec3 cameraPos;
uniform vec3 prevCameraPos;
uniform int motionBlurSamples;
int halfMotionBlurSamples = motionBlurSamples / 2;
uniform int blurAlgorithm;
in vec2 texCoord;
layout(location = 0) out vec4 color;

#define clamp01(x) clamp(x, 0.0, 1.0)
#define rcp(x) (1.0 / (x))

vec3 transform(mat4 m, vec3 pos) {
    return mat3(m) * pos + m[3].xyz;
}

vec3 project_and_divide(mat4 m, vec3 pos) {
    vec4 h = m * vec4(pos, 1.0);
    return h.xyz / h.w;
}

vec3 screen_to_scene_space(vec3 screen_pos) {
    vec3 ndc = 2.0 * screen_pos - 1.0;
    vec3 view_pos = project_and_divide(projInverse, ndc);
    return transform(mvInverse, view_pos);
}

vec3 reproject(vec3 screen_pos) {
    vec3 scene_pos = screen_to_scene_space(screen_pos);
    vec3 prev_pos = transform(prevModelView, scene_pos + (cameraPos - prevCameraPos));
    prev_pos = project_and_divide(prevProjection, prev_pos);
    return prev_pos * 0.5 + 0.5;
}

float noise(vec2 pos) {
    return fract(52.9829189 * fract(0.06711056 * pos.x + 0.00583715 * pos.y));
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);

    float depth = texelFetch(MainDepthSampler, texel, 0).x;
    vec2 velocity = texCoord - reproject(vec3(texCoord, depth)).xy;

    vec2 totalOffset = BlendFactor * velocity;
    vec2 baseStep = totalOffset / float(motionBlurSamples);

    vec3 color_sum = vec3(0.0);
    float weight_sum = 0.0;

    if (blurAlgorithm == 0) {
        for (int i = 0; i < motionBlurSamples; ++i) {
            float jitter = noise(texCoord * view_res + vec2(float(i), float(i) * 1.5));
            vec2 pos = texCoord + (float(i) + jitter) * baseStep;
            ivec2 tap = ivec2(pos * view_res);
            vec3 color = texelFetch(MainSampler, tap, 0).rgb;
            float weight = (clamp01(pos) == pos) ? 1.0 : 0.0;

            color_sum += color * color * weight;
            weight_sum += weight;
        }
    } else {
        for (int i = -halfMotionBlurSamples + 1; i <= halfMotionBlurSamples; ++i) {
            float jitter = noise(texCoord * view_res + vec2(float(i), float(i) * 1.5));
            vec2 pos = texCoord + (float(i) + jitter - 1.0) * baseStep;
            ivec2 tap = ivec2(pos * view_res);
            vec3 color = texelFetch(MainSampler, tap, 0).rgb;
            float weight = (clamp01(pos) == pos) ? 1.0 : 0.0;

            color_sum += color * color * weight;
            weight_sum += weight;
        }
    }
    if (weight_sum > 0.0) {
        color = vec4(sqrt(color_sum * rcp(weight_sum)), 1.0);
    } else {
        color = vec4(texelFetch(MainSampler, texel, 0).rgb, 1.0);
    }
}