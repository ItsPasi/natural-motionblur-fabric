#version 330
#extension GL_ARB_separate_shader_objects : require

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
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 color;

float farDepthValue() {return 0.0;}
float nearerDepth(float a, float b) {return max(a, b);}

vec3 reproject(vec3 screenPos) {
    vec3 ndc      = vec3(screenPos.xy * 2.0 - 1.0, screenPos.z);
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
    if (depth > 0.44) {color = texture(MainSampler, texCoord);return;}

    // Depth blend inconsistency fix
    float dilatedDepth = depth;
    dilatedDepth = nearerDepth(dilatedDepth, texelFetch(MainDepthSampler, texel + ivec2( 1,  0), 0).x);
    dilatedDepth = nearerDepth(dilatedDepth, texelFetch(MainDepthSampler, texel + ivec2(-1,  0), 0).x);
    dilatedDepth = nearerDepth(dilatedDepth, texelFetch(MainDepthSampler, texel + ivec2( 0,  1), 0).x);
    dilatedDepth = nearerDepth(dilatedDepth, texelFetch(MainDepthSampler, texel + ivec2( 0, -1), 0).x);
    // Camera blur with depth blur cancelation
    vec2 velFull   = texCoord - reproject(vec3(texCoord, dilatedDepth)).xy;
    vec2 velCamera = texCoord - reproject(vec3(texCoord, farDepthValue())).xy;
    float camMag   = dot(velCamera, velCamera);
    vec2 cameraComponent = camMag > 1e-12 ? velCamera * (clamp(dot(velFull, velCamera), 0.0, camMag) / camMag) : vec2(0.0);
    vec2 velocity = clampLength(velFull - cameraComponent);

    float speed = length(velocity);
    int boxSamples = clamp(int(ceil(speed * float(sampleCount))), 4, sampleCount);
    bool useSmooth = blurProfile == 1;
    int samples = useSmooth ? clamp(int(ceil(float(boxSamples) * 1.35)), 6, max(6, int(ceil(float(sampleCount) * 1.35)))) : boxSamples;

    vec2 step = blendFactor * velocity * (useSmooth ? 3.64 * 0.96 : 1.0) / float(samples);
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
        if (useSmooth) {
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
    } else if (useSmooth) {
        color = vec4(pow(clamp(sum / totalWeight, vec3(0.0), vec3(1.0)), vec3(1.0 / 2.2)), 1.0);
    } else {
        color = vec4(sqrt(sum / totalWeight), 1.0);
    }
}