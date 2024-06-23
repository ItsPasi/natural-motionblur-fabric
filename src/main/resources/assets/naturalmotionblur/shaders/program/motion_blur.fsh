#version 330 core

uniform sampler2D DiffuseSampler;
uniform sampler2D DiffuseDepthSampler;
uniform vec2 view_res;
uniform mat4 mvInverse;
uniform mat4 projInverse;
uniform mat4 prevModelView;
uniform mat4 prevProjection;
uniform mat4 projection;
uniform vec3 cameraPos;
uniform vec3 prevCameraPos;
uniform int motionBlurSamples;
uniform int blurAlgorithm;
in vec2 texCoord;

uniform float BlendFactor = 0.75;
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

    vec3 powerExponent = vec3(1.8);
    vec3 sqrtExponent = vec3(0.5556);

    for (int i = 0; i < motionBlurSamples; ++i) {
        if (blurAlgorithm == 0) {
            vec2 pos = texCoord + float(i) * 2 * increment;
            ivec2 tap = ivec2(pos * view_res);
            vec3 color = texelFetch(DiffuseSampler, tap, 0).rgb;
            float weight = (clamp01(pos) == pos) ? 1.0 : 0.0;

            color_sum += pow(color, powerExponent) * weight;
            weight_sum += weight;
        } else {
            vec2 pos_forward = texCoord + 0.5 * increment + float(i) * increment;
            vec2 pos_backward = texCoord - 0.5 * increment - float(i) * increment;
            ivec2 tap_forward = ivec2(pos_forward * view_res);
            ivec2 tap_backward = ivec2(pos_backward * view_res);
            vec3 color_forward = texelFetch(DiffuseSampler, tap_forward, 0).rgb;
            vec3 color_backward = texelFetch(DiffuseSampler, tap_backward, 0).rgb;
            float weight_forward = (clamp01(pos_forward) == pos_forward) ? 1.0 : 0.0;
            float weight_backward = (clamp01(pos_backward) == pos_backward) ? 1.0 : 0.0;

            color_sum += pow(color_forward, powerExponent) * weight_forward
            + pow(color_backward, powerExponent) * weight_backward;
            weight_sum += weight_forward + weight_backward;
        }
    }
    color = vec4(pow(color_sum * rcp(weight_sum), sqrtExponent), 1.0);
}