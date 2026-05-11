$input a_position, a_texcoord0
$output v_texcoord0
#include <bgfx_shader.sh>

uniform vec4 ViewportScale;

struct VertexInput {
    vec4 position;
    vec2 texcoord0;
};

struct VertexOutput {
    vec4 position;
    vec4 texcoord0;
};

void Vert(VertexInput vertInput, inout VertexOutput vertOutput) {
    vertOutput.position = vec4(vertInput.position.xy * 2.0 - vec2_splat(1.0), 0.0, 1.0);
    vec2 scaledTexcoord = vertInput.texcoord0 * ViewportScale.xy;
    vertOutput.texcoord0 = vec4(scaledTexcoord.x, scaledTexcoord.y, vertInput.texcoord0.x, vertInput.texcoord0.y);
}

void main() {
    VertexInput vertexInput;
    VertexOutput vertexOutput;
    
    vertexInput.position = a_position;
    vertexInput.texcoord0 = a_texcoord0;
    
    Vert(vertexInput, vertexOutput);
    
    v_texcoord0 = vertexOutput.texcoord0;
    gl_Position = vertexOutput.position;
}