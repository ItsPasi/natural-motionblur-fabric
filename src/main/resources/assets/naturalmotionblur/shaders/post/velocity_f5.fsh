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
    float blendFactor;
    int   sampleCount;
    int   blurProfile;
    int   useDepth;
    int   depthConvention; // 0 = vanilla 26.2 (0..1 reversed-Z), 1 = Iris shader-pack (-1..1 standard depth)
};

in vec2 texCoord;
layout(location = 0) out vec4 color;

float depthToNdc(float depth) {
    return depthConvention != 0 ? depth * 2.0 - 1.0 : depth;
}
float farDepthValue() {
    return depthConvention != 0 ? 1.0 : 0.0;
}
float nearerDepth(float a, float b) {
    return depthConvention != 0 ? min(a, b) : max(a, b);
}

// Hand / very-near protection.
bool shouldProtectHand(float depth) {
    float correctedDepth = depthConvention != 0 ? 1.0 - depth : depth;
    return correctedDepth > 0.44;
}

vec3 reproject(vec3 screenPos) {
    vec3 ndc      = vec3(screenPos.xy * 2.0 - 1.0, depthToNdc(screenPos.z));
    vec4 viewPos  = projInverse * vec4(ndc, 1.0);
    vec3 worldPos = (mvInverse * vec4(viewPos.xyz / viewPos.w, 1.0)).xyz + cameraDelta;
    vec4 prevClip = prevProjection * (prevModelView * vec4(worldPos, 1.0));
    return vec3((prevClip.xy / prevClip.w) * 0.5 + 0.5, prevClip.z / prevClip.w);
}

vec2 clampLength(vec2 velocity) {
    float lenSq = dot(velocity, velocity);
    return (lenSq > 0.16) ? velocity * (0.4 * inversesqrt(lenSq)) : velocity;
}

float noise(vec2 pos) {
    return fract(52.9829189 * fract(0.06711056 * pos.x + 0.00583715 * pos.y));
}

float blackmanSincWeight(float u) {
    float z = 1.89 * u;
    float sinc = abs(z) < 0.0001 ? 1.0 : sin(3.141592653589793 * z) / (3.141592653589793 * z);
    return sinc * (0.42 + 0.5 * cos(3.141592653589793 * u) + 0.08 * cos(6.28318530718 * u));
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(MainDepthSampler, texel, 0).x;

    // Iris Hand Fix
    if (shouldProtectHand(depth)) {
        color = texture(MainSampler, texCoord);
        return;
    }

    // Depth blend inconsistency fix
    float dilatedDepth = depth;
    dilatedDepth = nearerDepth(dilatedDepth, texelFetch(MainDepthSampler, texel + ivec2( 1,  0), 0).x);
    dilatedDepth = nearerDepth(dilatedDepth, texelFetch(MainDepthSampler, texel + ivec2(-1,  0), 0).x);
    dilatedDepth = nearerDepth(dilatedDepth, texelFetch(MainDepthSampler, texel + ivec2( 0,  1), 0).x);
    dilatedDepth = nearerDepth(dilatedDepth, texelFetch(MainDepthSampler, texel + ivec2( 0, -1), 0).x);

    vec2 velocity = clampLength(texCoord - reproject(vec3(texCoord, dilatedDepth)).xy);

    float speed = length(velocity);
    int baseSamples = clamp(int(ceil(speed * float(sampleCount))), 4, sampleCount);
    bool isWeighted = blurProfile == 1;
    int samples = isWeighted ? clamp(int(ceil(float(baseSamples) * 1.35)), 6, max(6, int(ceil(float(sampleCount) * 1.35)))) : baseSamples;

    vec2 step = blendFactor * velocity * (isWeighted ? 3.64 * 0.96 : 1.0) / float(samples);
    float centerOffset = -float(samples) * 0.5;
    vec2 seed = texCoord * view_res;
    vec3 sum = vec3(0.0);
    float totalWeight = 0.0;

    for (int i = 0; i < samples; i++) {
        float fi = float(i);
        float jitter = noise(seed + vec2(fi, fi * 1.4));
        float offset = fi + centerOffset + jitter;
        vec2 pos = texCoord + offset * step;
        vec3 c = texture(MainSampler, pos).rgb;
        if (isWeighted) {
            float weight = blackmanSincWeight(offset * 2.0 / float(samples));
            sum += pow(max(c, vec3(0.0)), vec3(2.2)) * weight;
            totalWeight += weight;
        } else {
            sum += c * c;
            totalWeight += 1.0;
        }
    }

    if (totalWeight <= 0.0001) {
        color = texture(MainSampler, texCoord);
    } else if (isWeighted) {
        color = vec4(pow(clamp(sum / totalWeight, vec3(0.0), vec3(1.0)), vec3(1.0 / 2.2)), 1.0);
    } else {
        color = vec4(sqrt(sum / totalWeight), 1.0);
    }
}