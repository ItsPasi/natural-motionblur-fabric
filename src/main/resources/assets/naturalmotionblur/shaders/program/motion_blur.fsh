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

vec4 project(mat4 m, vec3 pos) {
    return vec4(m[0].x, m[1].y, m[2].zw) * pos.xyzz + m[3];
}

vec3 project_and_divide(mat4 m, vec3 pos) {
    vec4 homogenous = project(m, pos);
    return homogenous.xyz / homogenous.w;
}

vec3 screen_to_view_space(vec3 screen_pos) {
    vec3 ndc_pos = 2.0 * screen_pos - 1.0;
    return project_and_divide(projInverse, ndc_pos);
}

vec3 view_to_scene_space(vec3 view_pos) {
    return transform(mvInverse, view_pos);
}

vec3 reproject_scene_space(vec3 scene_pos) {
    vec3 camera_offset = cameraPos - prevCameraPos;
    vec3 previous_pos = transform(prevModelView, scene_pos + camera_offset);
    previous_pos = project_and_divide(prevProjection, previous_pos);
    return previous_pos * 0.5 + 0.5;
}

vec3 reproject(vec3 screen_pos) {
    vec3 pos = screen_to_view_space(screen_pos);
    pos = view_to_scene_space(pos);
    return reproject_scene_space(pos);
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);

    float depth = texelFetch(DiffuseDepthSampler, texel, 0).x;
    vec2 velocity = texCoord - reproject(vec3(texCoord, depth)).xy;
    vec2 increment = (0.5 * BlendFactor / float(motionBlurSamples)) * velocity;

    vec3 color_sum = vec3(0.0);
    float weight_sum = 0.0;

    if (blurAlgorithm == 0) {
        for (int i = 0; i < motionBlurSamples; ++i) {
            vec2 pos = texCoord + float(i) * 2 * increment;
            ivec2 tap = ivec2(pos * view_res);
            vec3 color = texelFetch(DiffuseSampler, tap, 0).rgb;
            float weight = (clamp01(pos) == pos) ? 1.0 : 0.0;

            color_sum += color * color * weight;
            weight_sum += weight;
        }
    } else {
        for (int i = -halfMotionBlurSamples + 1; i <= halfMotionBlurSamples; ++i) {
            vec2 pos = texCoord + float(i) * 2 * increment;
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