#version 330

uniform sampler2D MainSampler;
uniform sampler2D PrevSampler;

uniform float blendFactor;

in vec2 texCoord;
in vec2 oneTexel;
uniform vec2 InSize;
out vec4 fragColor;

void main() {
    vec3 curr = texture(MainSampler, texCoord).rgb;
    vec3 prev = texture(PrevSampler, texCoord).rgb;
    vec3 currLinear = curr * curr;
    vec3 prevLinear = prev * prev;

    vec3 blended = mix(currLinear, prevLinear, blendFactor);
    fragColor = vec4(sqrt(blended), 1.0);
}