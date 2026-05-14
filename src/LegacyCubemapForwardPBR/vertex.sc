$input a_position, a_color0, a_texcoord0
#ifdef INSTANCING__ON
$input i_data1, i_data2, i_data3
#endif

$output v_clipPosition, v_color0, v_texcoord0, v_worldPos

#include <bgfx_shader.sh>

uniform mat4 CubemapRotation;
uniform vec4 SubPixelOffset;

void main() {
    mat4 model;
#ifdef INSTANCING__ON
    model = mtxFromRows(i_data1, i_data2, i_data3, vec4(0.0, 0.0, 0.0, 1.0));
#else
    model = u_model[0];
#endif

    vec3 worldPos = mul(model, mul(CubemapRotation, vec4(a_position, 1.0))).xyz;
    
    mat4 offsetProj = u_proj;
    offsetProj[2][0] += SubPixelOffset.x;
    offsetProj[2][1] -= SubPixelOffset.y;
    
    vec4 clipPosition = mul(offsetProj, mul(u_view, vec4(worldPos, 1.0)));
    
    v_clipPosition = clipPosition;
    v_color0 = a_color0;
    v_texcoord0 = a_texcoord0;
    v_worldPos = worldPos;
    
    gl_Position = clipPosition;
}
