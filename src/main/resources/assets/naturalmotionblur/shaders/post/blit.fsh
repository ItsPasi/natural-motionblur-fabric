#version 330

uniform sampler2D InSampler;

in vec2 texCoord;
layout(location = 0) out vec4 color;

void main() {
    color = texture(InSampler, texCoord);
}