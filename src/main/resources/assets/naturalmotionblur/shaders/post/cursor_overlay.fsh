#version 330

uniform sampler2D MainSampler;
uniform sampler2D CursorSampler;

layout(std140) uniform CursorOverlayUniforms {
    float cursorX;
    float cursorY;
    float cursorScale;
    float cursorVisible;
    float prevCursorX;
    float prevCursorY;
    float blurStrength;
    float padding0;
};

in vec2 texCoord;
layout(location = 0) out vec4 color;

float noise(vec2 pos) {
    return fract(52.9829189 * fract(0.06711056 * pos.x + 0.00583715 * pos.y));
}

vec4 sampleCursorNearest(vec2 localPx, ivec2 cursorSize) {
    ivec2 ipx = ivec2(floor(localPx));
    if (ipx.x < 0 || ipx.y < 0 || ipx.x >= cursorSize.x || ipx.y >= cursorSize.y) {
        return vec4(0.0);
    }
    return texelFetch(CursorSampler, ipx, 0);
}

void main() {
    vec4 base = texture(MainSampler, texCoord);

    if (cursorVisible < 0.5) {
        color = base;
        return;
    }

    vec2 mainSizeF = vec2(textureSize(MainSampler, 0));
    ivec2 cursorSize = textureSize(CursorSampler, 0);

    vec2 pixelPos = vec2(
    floor(texCoord.x * mainSizeF.x),
    floor((1.0 - texCoord.y) * mainSizeF.y)
    );

    vec2 current  = floor(vec2(cursorX, cursorY));
    vec2 previous = floor(vec2(prevCursorX, prevCursorY));

    float scale = max(cursorScale, 0.001);
    vec2 trail = (current - previous) * max(blurStrength, 0.0);
    vec2 start = current - trail;

    const int SAMPLES = 8;

    vec3 accumRgb = vec3(0.0);
    float accumA = 0.0;

    vec2 seed = pixelPos;

    for (int i = 0; i < SAMPLES; ++i) {
        float fi = float(i);
        float jitter = noise(seed + vec2(fi, fi * 1.4));
        float t = (fi + jitter) / float(SAMPLES);

        vec2 pos = mix(start, current, t);
        vec2 localPx = (pixelPos - pos) / scale;

        vec4 c = sampleCursorNearest(localPx, cursorSize);

        vec3 cLin = c.rgb * c.rgb;
        accumRgb += cLin * c.a;
        accumA += c.a;
    }

    float cursorA = clamp(accumA / float(SAMPLES), 0.0, 1.0);
    vec3 cursorLin = (accumA > 0.0) ? (accumRgb / accumA) : vec3(0.0);

    vec3 baseLin = base.rgb * base.rgb;
    vec3 outLin = mix(baseLin, cursorLin, cursorA);

    color = vec4(sqrt(max(outLin, vec3(0.0))), base.a);
}