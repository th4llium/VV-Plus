vec2 a_texcoord0 : TEXCOORD0;
vec3 a_position : POSITION;
vec4 a_color0 : COLOR0;

vec4 v_clipPosition : TEXCOORD1;
vec4 v_color0 : COLOR0;
vec2 v_texcoord0 : TEXCOORD0;
vec3 v_worldPos : TEXCOORD2;

#ifdef INSTANCING__ON
vec4 i_data1 : TEXCOORD3;
vec4 i_data2 : TEXCOORD4;
vec4 i_data3 : TEXCOORD5;
#endif
