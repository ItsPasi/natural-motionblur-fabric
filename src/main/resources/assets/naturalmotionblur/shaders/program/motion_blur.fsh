#version 330 core

uniform sampler2D DiffuseSampler;
uniform sampler2D DiffuseDepthSampler;
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

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);

    float depth = texelFetch(DiffuseDepthSampler, texel, 0).x;
    vec2 velocity = texCoord - reproject(vec3(texCoord, depth)).xy;
    vec2 increment = (BlendFactor / float(motionBlurSamples)) * velocity;

    vec3 color_sum = vec3(0.0);
    float weight_sum = 0.0;

    if (blurAlgorithm == 0) {
        for (int i = 0; i < motionBlurSamples; ++i) {
            vec2 pos = texCoord + float(i) * increment;
            ivec2 tap = ivec2(pos * view_res);
            vec3 color = texelFetch(DiffuseSampler, tap, 0).rgb;
            float weight = (clamp01(pos) == pos) ? 1.0 : 0.0;

            color_sum += color * color * weight;
            weight_sum += weight;
        }
    } else {
        for (int i = -halfMotionBlurSamples + 1; i <= halfMotionBlurSamples; ++i) {
            vec2 pos = texCoord + float(i) * increment;
            ivec2 tap = ivec2(pos * view_res);
            vec3 color = texelFetch(DiffuseSampler, tap, 0).rgb;
            float weight = (clamp01(pos) == pos) ? 1.0 : 0.0;

            color_sum += color * color * weight;
            weight_sum += weight;
        }
    }
    if (weight_sum > 0.0) {
        color = vec4(sqrt(color_sum * rcp(weight_sum)), 1.0);
    } else {
        color = vec4(texelFetch(DiffuseSampler, texel, 0).rgb, 1.0);
    }
}