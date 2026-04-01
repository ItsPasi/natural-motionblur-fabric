#version 330

uniform sampler2D MainSampler;
uniform sampler2D MainDepthSampler;

layout(std140) uniform PreEntityBlurUniforms {
    mat4 mvInverse;
    mat4 projInverse;
    mat4 prevModelView;
    mat4 prevProjection;
    vec3 cameraDelta;
    vec2 view_res;
    float BlendFactor;
    int motionBlurSamples;
    int blurAlgorithm;
    int useDepth;
};

in vec2 texCoord;
layout(location = 0) out vec4 color;

vec3 reproject(vec3 screen_pos) {
    vec3 ndc = screen_pos * 2.0 - 1.0;
    vec4 view_pos4 = projInverse * vec4(ndc, 1.0);
    vec3 view_pos = view_pos4.xyz / view_pos4.w;

    vec3 world_pos = (mvInverse * vec4(view_pos, 1.0)).xyz + cameraDelta;
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
    // Iris Hand Fix
    if (depth < 0.56) {
        color = texture(MainSampler, texCoord);
        return;
    }
    // Depth blend inconsistency fix
    float dilatedDepth = depth;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            float d = texelFetch(MainDepthSampler, texel + ivec2(x, y), 0).x;
            dilatedDepth = min(dilatedDepth, d);
        }
    }
    // Motion Cancelation Logic
    vec2 vel_full   = texCoord - reproject(vec3(texCoord, dilatedDepth)).xy;
    vec2 vel_camera = texCoord - reproject(vec3(texCoord, 1.0)).xy;

    float camMag = dot(vel_camera, vel_camera);
    vec2 velocity = camMag > 1e-12
    ? vel_full - vel_camera * (clamp(dot(vel_full, vel_camera), 0.0, camMag) / camMag)
    : vel_full;

    velocity = clampLength(velocity);

    float speed = length(velocity);
    int dynamicSamples = clamp(int(ceil(speed * float(motionBlurSamples))), 4, motionBlurSamples);

    vec2 baseStep = (BlendFactor * velocity) / float(dynamicSamples);
    vec3 color_sum = vec3(0.0);
    vec2 seed = texCoord * view_res;
    // VELOCITY_BASED (0) uses centered sampling — no perceived input lag increase
    float centerOffset = -(float(dynamicSamples) * 0.5);

    for (int i = 0; i < dynamicSamples; ++i) {
        float fi = float(i);

        float jitter = noise(seed + vec2(fi, fi * 1.4));
        vec2 pos = texCoord + (fi + centerOffset + jitter) * baseStep;
        vec3 color = texture(MainSampler, pos).rgb;

        color_sum += color * color;
    }
    color = vec4(sqrt(color_sum / float(dynamicSamples)), 1.0);
}