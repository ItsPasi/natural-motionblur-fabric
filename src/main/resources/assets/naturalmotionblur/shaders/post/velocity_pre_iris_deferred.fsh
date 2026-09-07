#version 330

uniform sampler2D MainSampler;
uniform sampler2D MainDepthSampler;
uniform sampler2D PreDepthSampler;

layout(std140) uniform PreEntityBlurUniforms {
    mat4 mvInverse;
    mat4 projInverse;
    mat4 prevModelView;
    mat4 prevProjection;
    vec3 cameraDelta;
    vec2 view_res;
    float blendFactor;
    int   sampleCount;
    int   blurAlgorithm;
    int   useDepth;
    int   depthConvention; // 1 = Iris shader-pack (-1..1 standard depth)
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

ivec2 clampTexel(ivec2 p, ivec2 size) {
    return clamp(p, ivec2(0), size - ivec2(1));
}

bool stillPreEntitySurface(ivec2 texel) {
    float beforeEntities = texelFetch(PreDepthSampler, texel, 0).x;
    float afterEntities  = texelFetch(MainDepthSampler, texel, 0).x;
    return abs(beforeEntities - afterEntities) <= 0.0000005;
}

void main() {
    ivec2 size = textureSize(PreDepthSampler, 0);
    ivec2 texel = clampTexel(ivec2(gl_FragCoord.xy), size);

    if (!stillPreEntitySurface(texel)) {
        color = texture(MainSampler, texCoord);
        return;
    }

    float depth = texelFetch(PreDepthSampler, texel, 0).x;

    float dilatedDepth = depth;
    dilatedDepth = nearerDepth(dilatedDepth, texelFetch(PreDepthSampler, clampTexel(texel + ivec2( 1,  0), size), 0).x);
    dilatedDepth = nearerDepth(dilatedDepth, texelFetch(PreDepthSampler, clampTexel(texel + ivec2(-1,  0), size), 0).x);
    dilatedDepth = nearerDepth(dilatedDepth, texelFetch(PreDepthSampler, clampTexel(texel + ivec2( 0,  1), size), 0).x);
    dilatedDepth = nearerDepth(dilatedDepth, texelFetch(PreDepthSampler, clampTexel(texel + ivec2( 0, -1), size), 0).x);

    vec2 velFull   = texCoord - reproject(vec3(texCoord, dilatedDepth)).xy;
    vec2 velCamera = texCoord - reproject(vec3(texCoord, farDepthValue())).xy;
    float camMag   = dot(velCamera, velCamera);

    vec2 velocity;
    if (useDepth == 2) {
        velocity = clampLength(velFull);
    } else {
        velocity = clampLength(
            camMag > 1e-12
                ? velFull - velCamera * (clamp(dot(velFull, velCamera), 0.0, camMag) / camMag)
                : velFull
        );
    }

    float speed = length(velocity);
    int samples = clamp(int(ceil(speed * float(sampleCount))), 4, sampleCount);

    vec2 step = (blendFactor * velocity) / float(samples);
    float centerOffset = -float(samples) * 0.5;
    vec2 seed = texCoord * view_res;
    vec3 sum = vec3(0.0);
    int validSamples = 0;

    for (int i = 0; i < samples; i++) {
        float fi = float(i);
        float jitter = noise(seed + vec2(fi, fi * 1.4));
        vec2 pos = texCoord + (fi + centerOffset + jitter) * step;
        ivec2 sampleTexel = clampTexel(ivec2(pos * view_res), size);

        if (!stillPreEntitySurface(sampleTexel)) {
            continue;
        }

        vec3 c = texture(MainSampler, pos).rgb;
        sum += c * c;
        validSamples++;
    }

    if (validSamples == 0) {
        color = texture(MainSampler, texCoord);
    } else {
        color = vec4(sqrt(sum / float(validSamples)), 1.0);
    }
}
