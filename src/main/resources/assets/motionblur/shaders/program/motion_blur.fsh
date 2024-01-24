#version 330 core

uniform sampler2D DiffuseSampler;
uniform sampler2D DiffuseDepthSampler;
uniform sampler2D PrevSampler;
uniform vec2 view_res;
uniform vec2 view_pixel_size;
uniform mat4 mvInverse;
uniform mat4 projInverse;
uniform mat4 prevModelView;
uniform mat4 prevProjection;
uniform mat4 projection;
uniform vec3 cameraPos;
uniform vec3 prevCameraPos;

in vec2 texCoord;
in vec2 oneTexel;

uniform vec2 InSize;

uniform float BlendFactor = 0.75;

layout(location = 0) out vec4 color;
#define MOTION_BLUR_SAMPLES 20
#define clamp01(x) clamp(x, 0.0, 1.0) // free on operation output
#define rcp(x) (1.0 / (x))

vec2 diagonal(mat2 m) { return vec2(m[0].x, m[1].y); }
vec3 diagonal(mat3 m) { return vec3(m[0].x, m[1].y, m[2].z); }
vec4 diagonal(mat4 m) { return vec4(m[0].x, m[1].y, m[2].z, m[3].w); }

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

vec3 project_ortho(mat4 m, vec3 pos) {
    return diagonal(m).xyz * pos + m[3].xyz;
}

vec3 screen_to_view_space(vec3 screen_pos) {
    vec3 ndc_pos = 2.0 * screen_pos - 1.0;

    return project_and_divide(projInverse, ndc_pos);
}

vec3 view_to_scene_space(vec3 view_pos) {
    return transform(mvInverse, view_pos);
}

vec3 view_to_screen_space(vec3 view_pos) {
    vec3 ndc_pos = project_and_divide(projection, view_pos);

    return ndc_pos * 0.5 + 0.5;
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
    ivec2 texel      = ivec2(gl_FragCoord.xy);
    ivec2 view_texel = ivec2(gl_FragCoord.xy);

    float depth = texelFetch(DiffuseDepthSampler, view_texel, 0).x;

    vec2 velocity = texCoord - reproject(vec3(texCoord, depth)).xy;

    vec2 pos = texCoord;
    vec2 increment = (0.5 * BlendFactor / float(MOTION_BLUR_SAMPLES)) * velocity;

    vec3 color_sum = vec3(0.0);
    float weight_sum = 0.0;

    for (int i = 0; i < MOTION_BLUR_SAMPLES; ++i, pos += increment) {
        ivec2 tap      = ivec2(pos * view_res);
        ivec2 view_tap = ivec2(pos * view_res);

        vec3 color = texelFetch(DiffuseSampler, tap, 0).rgb;
        float depth = texelFetch(DiffuseDepthSampler, view_tap, 0).x;
        float weight = (clamp01(pos) == pos) ? 1.0 : 0.0;

        color_sum += color * weight;
        weight_sum += weight;
    }

    color = vec4(color_sum * rcp(weight_sum), 1.0);
}