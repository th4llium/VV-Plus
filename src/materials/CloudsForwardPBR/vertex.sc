$input a_texcoord4, a_color0, a_normal, a_position, a_texcoord0
#ifdef INSTANCING__ON
$input i_data1, i_data2, i_data3
#endif
$output v_adjacentClouds, v_color0, v_normal, v_tilePosition, v_worldPos

#include <bgfx_shader.sh>

#ifndef DEPTH_ONLY_PASS
uniform vec4 CloudColor;
uniform vec4 CloudLightingToggles;
uniform vec4 SubPixelOffset;
#endif

#ifndef DEPTH_ONLY_PASS
vec4 jitterVertexPosition(vec3 worldPosition) {
    mat4 offsetProj = u_proj;
    vec4 col2 = offsetProj[2];
    col2.x += SubPixelOffset.x;
    col2.y -= SubPixelOffset.y;
    mat4 jitteredProj = mtxFromCols(u_proj[0], u_proj[1], col2, u_proj[3]);
    return mul(jitteredProj, mul(u_view, vec4(worldPosition, 1.0)));
}
#endif

void main() {
#ifdef INSTANCING__OFF
    vec3 worldPos = mul(u_model[0], vec4(a_position, 1.0)).xyz;
#else
    mat4 model = mtxFromCols(
        vec4(i_data1.x, i_data2.x, i_data3.x, 0.0),
        vec4(i_data1.y, i_data2.y, i_data3.y, 0.0),
        vec4(i_data1.z, i_data2.z, i_data3.z, 0.0),
        vec4(i_data1.w, i_data2.w, i_data3.w, 1.0)
    );
    vec3 worldPos = mul(model, vec4(a_position, 1.0)).xyz;
#endif

#ifdef DEPTH_ONLY_PASS
    vec4 clipPos = mul(u_viewProj, vec4(worldPos, 1.0));
    clipPos.z = clamp(clipPos.z, -1.0, 1.0);

    v_adjacentClouds = 0;
    v_color0 = vec4_splat(0.0);
    v_normal = vec3_splat(0.0);
    v_tilePosition = vec2_splat(0.0);
    v_worldPos = worldPos;
    gl_Position = clipPos;
#else
    int cloudData = a_texcoord4;
    vec2 tilePos = vec2_splat(0.0);
    vec3 cloudColor = clamp(CloudColor.xyz * a_color0.xyz, vec3_splat(0.0), vec3_splat(1.0));
    vec3 normal;
    int adjacentFlags;

    if (CloudLightingToggles.z != 0.0) {
        tilePos.x = ((cloudData & 256) != 0) ? 0.0 : 16.0;
        tilePos.y = ((cloudData & 512) != 0) ? 0.0 : 16.0;
        adjacentFlags = cloudData;
        normal = a_normal.xyz;
    } else {
        adjacentFlags = 0;
        normal = vec3_splat(0.0);
    }

    v_adjacentClouds = adjacentFlags;
    v_color0 = vec4(cloudColor.x, cloudColor.y, cloudColor.z, CloudColor.w);
    v_normal = normal;
    v_tilePosition = tilePos;
    v_worldPos = worldPos;
    gl_Position = jitterVertexPosition(worldPos);
#endif
}
