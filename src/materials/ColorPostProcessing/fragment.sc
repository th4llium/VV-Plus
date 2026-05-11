$input v_texcoord0
#include <bgfx_shader.sh>

// ColorPostProcessing buffers
SAMPLER2D_HIGHP_AUTOREG(s_AverageLuminance);
SAMPLER2D_HIGHP_AUTOREG(s_ColorTexture);
SAMPLER2D_HIGHP_AUTOREG(s_CustomExposureCompensation);
SAMPLER2D_HIGHP_AUTOREG(s_PreExposureLuminance);
SAMPLER2D_HIGHP_AUTOREG(s_RasterizedColor);
SAMPLER2D_HIGHP_AUTOREG(s_RasterColor);

// ColorPostProcessing uniforms
uniform vec4 ColorGrading_Contrast_Highlights;
uniform vec4 ColorGrading_Contrast_Midtones;
uniform vec4 ColorGrading_Contrast_Shadows;
uniform vec4 ColorGrading_Gain_Highlights;
uniform vec4 ColorGrading_Gain_Midtones;
uniform vec4 ColorGrading_Gain_Shadows;
uniform vec4 ColorGrading_Gamma_Highlights;
uniform vec4 ColorGrading_Gamma_Midtones;
uniform vec4 ColorGrading_Gamma_Shadows;
uniform vec4 ColorGrading_Misc2;
uniform vec4 ColorGrading_Misc;
uniform vec4 ColorGrading_Offset_Highlights;
uniform vec4 ColorGrading_Offset_Midtones;
uniform vec4 ColorGrading_Offset_Shadows;
uniform vec4 ColorGrading_Saturation_Highlights;
uniform vec4 ColorGrading_Saturation_Midtones;
uniform vec4 ColorGrading_Saturation_Shadows;
uniform vec4 ColorGrading_Temperature_Params;
uniform vec4 ExposureCompensation;
uniform vec4 GenericTonemapperContrastAndScaleAndOffsetAndCrosstalk;
uniform vec4 GenericTonemapperCrosstalkParams;
uniform vec4 LuminanceMinMaxAndWhitePointAndMinWhitePoint;
uniform vec4 RasterizedColorEnabled;
uniform vec4 TonemapParams0;
uniform vec4 ElapsedFrameTime;
uniform vec4 ViewportScale;
uniform vec4 ScreenSize;

// Custom uniforms that are not used in vanilla, but can be used by shaderpacks for various purposes.
uniform vec4 WeatherID;
uniform vec4 BiomeID;
uniform vec4 CloudHeight;
uniform vec4 Day;
uniform vec4 DimensionID;
uniform vec4 LocalClientID;
uniform vec4 MoonPhase;
uniform vec4 RenderDistance;
uniform vec4 TimeOfDay;
uniform vec4 CameraFacingDirection;
uniform vec4 CameraPosition;
uniform vec4 LastCameraFacingDirection;
uniform vec4 LastCameraPosition;
uniform vec4 SunDirection;
uniform vec4 CloudColor;
uniform vec4 FogColor;

/*
    A tonemapping shader for the Vibrant Visual (deferred) pipeline for Minecraft Bedrock Edition.
    This shader is responsible for tonemapping the HDR scene color, applying color grading, and applying a color temperature adjustment, with some improvements to the vanilla source code.
    CREDITS:
    - The obsfucated shader source code by Veka. Source: https://github.com/veka0/mcbe-shader-codebase/tree/release/obfuscated/materials/ColorPostProcessing
    - "Fork AgX Minima troy_s 342" made by troy_s. Source: https://www.shadertoy.com/view/mdcSDH
    - Krzysztof Narkowicz 2016, "ACES Filmic Tone Mapping Curve". Source: https://knarkowicz.wordpress.com/2016/01/06/aces-filmic-tone-mapping-curve/
    - The creator of the code, Mojang.
    - Modified by, Thallium.

    TODO:
    - Replace all Reinhards with modern tonemapping operators that have better results (Khronos PBR Neutral, GT7, and maybe Lottes?).
    - Much better color grading, I definitely can improve them for better results.
*/

struct FragmentInput {
    vec4 texcoord0;
};

struct FragmentOutput {
    vec4 Color0;
};

float luminance(vec3 clr) {
    return dot(clr, vec3(0.2126, 0.7152, 0.0722));
}

float luminanceToEV100(float lum) {
    return log2(lum) + 3.0;
}

vec3 getGradingVector(vec4 highlights, vec4 shadows, vec4 midtones, float averageLuminance, float colorLuminance, vec3 defaultVal) {
    float highlightTest = highlights.w;
    float shadowTest = shadows.w;
    
    bool isHighlight = (highlightTest != 0.0) ? (colorLuminance >= (averageLuminance * ColorGrading_Misc.y)) : false;
    bool isShadow = (shadowTest != 0.0) ? (colorLuminance <= (averageLuminance * ColorGrading_Misc.z)) : false;
    
    if (isHighlight) {
        return highlights.xyz;
    } else if (isShadow) {
        return shadows.xyz;
    } else {
        if (midtones.w != 0.0) {
            if ((colorLuminance < averageLuminance) && (shadowTest != 0.0)) {
                return mix(shadows.xyz, midtones.xyz, vec3_splat((colorLuminance - (averageLuminance * ColorGrading_Misc.z)) / (averageLuminance - (averageLuminance * ColorGrading_Misc.z))));
            } else if ((colorLuminance > averageLuminance) && (highlightTest != 0.0)) {
                return mix(midtones.xyz, highlights.xyz, vec3_splat((colorLuminance - averageLuminance) / ((averageLuminance * ColorGrading_Misc.y) - averageLuminance)));
            } else {
                return midtones.xyz;
            }
        } else {
            return defaultVal;
        }
    }
}

vec3 UnExposeLighting(vec3 color, float preExposureLuminance) {
    return color / vec3_splat((0.18 / preExposureLuminance) + 0.0001);
}

vec3 ApplyTemperature(vec3 inColor) {
    if (ColorGrading_Temperature_Params.x == 0.0) {
        return inColor;
    }

    vec3 defaultIlluminant = vec3(0.31272, 0.32903, 1.0);
    mat3 rgbToXyz = mtxFromRows(
        vec3(0.7328, 0.4296, -0.1624),
        vec3(-0.7036, 1.6975, 0.0061),
        vec3(0.0030, 0.0136, 0.9834)
    );
    vec3 illuminantXyz = vec3((defaultIlluminant.x * defaultIlluminant.z) / defaultIlluminant.y, defaultIlluminant.z, (((1.0 - defaultIlluminant.x) - defaultIlluminant.y) * defaultIlluminant.z) / defaultIlluminant.y);
    vec3 defaultIlluminantColor = mul(rgbToXyz, illuminantXyz);
    
    float temperature = ColorGrading_Temperature_Params.y;
    vec2 temperatureXy = vec2(((0.8601177 + (0.0001541182 * temperature)) + ((0.0000001286412 * temperature) * temperature)) / ((1.0 + (0.0008424202 * temperature)) + ((0.0000007081451 * temperature) * temperature)), ((0.3173987 + (0.0000422806 * temperature)) + ((0.00000004204816 * temperature) * temperature)) / ((1.0 - (0.00002897418 * temperature)) + ((0.0000001614560 * temperature) * temperature)));
    vec3 temperatureXyz = vec3(vec2(3.0 * temperatureXy.x, 2.0 * temperatureXy.y) / vec2_splat(((2.0 * temperatureXy.x) - (8.0 * temperatureXy.y)) + 4.0), 1.0);
    
    vec3 temperatureColorXyz = vec3((temperatureXyz.x * temperatureXyz.z) / temperatureXyz.y, temperatureXyz.z, (((1.0 - temperatureXyz.x) - temperatureXyz.y) * temperatureXyz.z) / temperatureXyz.y);
    vec3 temperatureColor = mul(rgbToXyz, temperatureColorXyz);
    
    vec3 temperatureRatio;
    if (ColorGrading_Temperature_Params.z == 0.0) {
        temperatureRatio = defaultIlluminantColor / temperatureColor;
    } else {
        temperatureRatio = temperatureColor / defaultIlluminantColor;
    }
    
    mat3 lmsToRgb = mtxFromRows(
        vec3(2.8590, -1.6290, -0.0250),
        vec3(-0.2100, 1.1580, 0.0),
        vec3(-0.0420, -0.1180, 1.0690)
    );
    mat3 rgbToLms = mtxFromRows(
        vec3(0.3900, 0.5500, 0.0090),
        vec3(0.0710, 0.9630, 0.0010),
        vec3(0.0230, 0.1280, 0.9360)
    );
    
    vec3 lmsColor = mul(rgbToLms, inColor);
    lmsColor = lmsColor * temperatureRatio;
    return mul(lmsToRgb, lmsColor);
}

vec3 ApplyContrast(vec3 inColor, vec3 contrast, float contrastPivot) {
    vec3 pivotVec = vec3_splat(contrastPivot);
    vec3 colorClamp = max(inColor, vec3_splat(0.0));
    vec3 outColor = (pivotVec * pow(colorClamp / pivotVec, contrast));
    return max(outColor, vec3_splat(0.0));
}

vec3 ApplySaturation(vec3 inColor, vec3 saturation) {
    float lumi = luminance(inColor);
    return mix(vec3_splat(lumi), inColor, saturation);
}

vec3 ApplyGain(vec3 inColor, vec3 gain) {
    return max(inColor * gain, vec3_splat(0.0));
}

vec3 ApplyOffset(vec3 inColor, vec3 offset) {
    return inColor + offset;
}

vec3 ApplyColorGrading(vec3 inColor, float averageLuminance) {
    vec3 outColor = inColor;
    float finalLuminance = luminance(inColor);
    outColor = ApplyContrast(outColor, getGradingVector(ColorGrading_Contrast_Highlights, ColorGrading_Contrast_Shadows, ColorGrading_Contrast_Midtones, averageLuminance, finalLuminance, vec3_splat(1.0)), ColorGrading_Misc.x * averageLuminance);
    outColor = ApplySaturation(outColor, getGradingVector(ColorGrading_Saturation_Highlights, ColorGrading_Saturation_Shadows, ColorGrading_Saturation_Midtones, averageLuminance, finalLuminance, vec3_splat(1.0)));
    outColor = ApplyGain(outColor, getGradingVector(ColorGrading_Gain_Highlights, ColorGrading_Gain_Shadows, ColorGrading_Gain_Midtones, averageLuminance, finalLuminance, vec3_splat(1.0)));
    outColor = ApplyOffset(outColor, vec3_splat(averageLuminance) * getGradingVector(ColorGrading_Offset_Highlights, ColorGrading_Offset_Shadows, ColorGrading_Offset_Midtones, averageLuminance, finalLuminance, vec3_splat(0.0)));
    return outColor;
}

vec3 TonemapReinhard(vec3 rgb) {
    return rgb / (vec3_splat(1.0) + rgb);
}

vec3 TonemapReinhardLuma(vec3 rgb, float W) {
    return (rgb * (vec3_splat(1.0) + (rgb / vec3_splat(W)))) / (vec3_splat(1.0) + rgb);
}

vec3 TonemapReinhardLuminance(vec3 rgb, float W) {
    float l_old = luminance(rgb);
    float l_new = (l_old * (1.0 + (l_old / W))) / (1.0 + l_old);
    return rgb * (l_new / l_old);
}

vec3 HableTonemap(vec3 x) {
    float A = 0.15;
    float B = 0.50;
    float C = 0.10;
    float D = 0.20;
    float E = 0.02;
    float F = 0.30;
    return ((x * (A * x + vec3_splat(C * B)) + vec3_splat(D * E)) / (x * (A * x + vec3_splat(B)) + vec3_splat(D * F))) - vec3_splat(E / F);
}

vec3 TonemapHable(vec3 rgb, float W) {
    float ExposureBias = 2.0;
    vec3 curr = HableTonemap(rgb * ExposureBias);
    vec3 whiteScale = vec3_splat(1.0) / HableTonemap(vec3_splat(W));
    return curr * whiteScale;
}

vec3 TonemapACES(vec3 rgb) {
    float ExposureBias = 1.2;
    vec3 x = rgb * ExposureBias;
    
    vec3 num = x * (2.51 * x + vec3_splat(0.03));
    vec3 den = x * (2.43 * x + vec3_splat(0.59)) + vec3_splat(0.14);
    
    return clamp(num / den, vec3_splat(0.0), vec3_splat(1.0));
}

/*
// A vanilla generic tonemapping made by Mojang for Vibrant Visuals, isn't used due to art direction. Replaced with AgX Punchy.
vec3 TonemapGeneric(vec3 rgb) {
    float peak = max(rgb.x, max(rgb.y, rgb.z));
    vec3 ratio = rgb / vec3_splat(peak);
    float contrastScl = pow(peak, GenericTonemapperContrastAndScaleAndOffsetAndCrosstalk.x);
    float genericTone = contrastScl / ((GenericTonemapperContrastAndScaleAndOffsetAndCrosstalk.y * contrastScl) + GenericTonemapperContrastAndScaleAndOffsetAndCrosstalk.z);
    
    vec3 mixedRatio = mix(pow(ratio, vec3_splat(1.0 / GenericTonemapperCrosstalkParams.x)), vec3_splat(1.0), vec3_splat(pow(genericTone, GenericTonemapperContrastAndScaleAndOffsetAndCrosstalk.w)));
    return pow(mixedRatio, vec3_splat(GenericTonemapperCrosstalkParams.x)) * genericTone;
}
*/

vec3 AgX_Log2(vec3 val) {
    mat3 agx_mat = mtxFromRows(
        vec3(0.842479062253094, 0.0784335999999992, 0.0792237451477643),
        vec3(0.0423282422610123, 0.878468636469772, 0.0791661274605434),
        vec3(0.0423756549057051, 0.0784336, 0.879142973793104)
    );
    vec3 clamped_val = max(val, vec3_splat(1e-10));
    vec3 mapped_val = mul(agx_mat, clamped_val);
    vec3 log_val = clamp(log2(mapped_val), vec3_splat(-12.47393), vec3_splat(4.026069));
    return (log_val + vec3_splat(12.47393)) / vec3_splat(16.5);
}

vec3 AgX_DefaultContrastApprox(vec3 x) {
    return x * (vec3_splat(0.12410293) + x * (vec3_splat(0.2078625) + x * (vec3_splat(-5.9293431) + x * (vec3_splat(30.376821) + x * (vec3_splat(-38.901506) + x * vec3_splat(15.122061))))));
}

vec3 AgX_ApplyPunchyLook(vec3 val) {
    float luma = dot(val, vec3(0.2126, 0.7152, 0.0722));
    vec3 valPow = pow(max(val, vec3_splat(0.0)), vec3_splat(1.35));
    return vec3_splat(luma) + vec3_splat(1.4) * (valPow - vec3_splat(luma));
}

vec3 AgX_InverseTransform(vec3 val) {
    mat3 agx_mat_inv = mtxFromRows(
        vec3(1.19687900512017, -0.0980208811401368, -0.0990297440797205),
        vec3(-0.0528968517574562, 1.15190312990417, -0.0989611768448433),
        vec3(-0.0529716355144438, -0.0980434501171241, 1.15107367264116)
    );
    return mul(agx_mat_inv, val);
}

vec3 TonemapGeneric(vec3 rgb) {
    vec3 agx_val = AgX_Log2(rgb);
    agx_val = AgX_DefaultContrastApprox(agx_val);
    agx_val = AgX_ApplyPunchyLook(agx_val);
    agx_val = AgX_InverseTransform(agx_val);
    return clamp(agx_val, vec3_splat(0.0), vec3_splat(1.0));
}

vec3 ApplyTonemap(vec3 sceneColor, float averageLuminance, float compensation, float whitePoint, float tonemapperType) {
    float toneMappedAverageLuminance = 0.18;
    float exposure = (toneMappedAverageLuminance / averageLuminance) * compensation;
    vec3 exposedColor = sceneColor * exposure;
    
    float scaledWhitePoint = exposure * whitePoint;
    float whitePointSquared = scaledWhitePoint * scaledWhitePoint;

    if (tonemapperType >= 1.0 && tonemapperType < 2.0) {
        return TonemapReinhardLuma(exposedColor, whitePointSquared);
    } else if (tonemapperType >= 2.0 && tonemapperType < 3.0) {
        return TonemapReinhardLuminance(exposedColor, whitePointSquared);
    } else if (tonemapperType >= 3.0 && tonemapperType < 4.0) {
        return TonemapHable(exposedColor, whitePointSquared);
    } else if (tonemapperType >= 4.0 && tonemapperType < 5.0) {
        return TonemapACES(exposedColor);
    } else if (tonemapperType >= 5.0 && tonemapperType < 6.0) {
        return TonemapGeneric(exposedColor);
    } else {
        return TonemapReinhard(exposedColor);
    }
}

vec3 color_gamma(vec3 clr, vec3 e) {
    if (ColorGrading_Misc2.x != 0.0) {
        return pow(max(clr, vec3_splat(0.0)), vec3_splat(1.0) / e);
    } else {
        vec3 srgbTone = clr;
        vec3 linearTone = clr * 12.92;
        vec3 expTone = (pow(abs(clr), vec3_splat(0.4166666)) * 1.055) - vec3_splat(0.055);
        srgbTone.x = (srgbTone.x <= 0.0031308) ? linearTone.x : expTone.x;
        srgbTone.y = (srgbTone.y <= 0.0031308) ? linearTone.y : expTone.y;
        srgbTone.z = (srgbTone.z <= 0.0031308) ? linearTone.z : expTone.z;
        return pow(srgbTone, vec3_splat(2.2) / e);
    }
}

void Frag(FragmentInput fragInput, inout FragmentOutput fragOutput) {
    vec2 uv = fragInput.texcoord0.xy;
    vec2 unscaledUv = fragInput.texcoord0.zw;
    
    vec2 edgeDist = min(unscaledUv, vec2_splat(1.0) - unscaledUv);
    vec2 edgeFade = smoothstep(vec2_splat(0.0), vec2_splat(0.025), edgeDist);
    float caMask = min(edgeFade.x, edgeFade.y);
    
    vec2 caOffset = (unscaledUv - vec2_splat(0.5)) * 0.005 * ViewportScale.xy * caMask;
    
    vec2 uvR = clamp(uv - caOffset, vec2_splat(0.0), ViewportScale.xy);
    vec2 uvB = clamp(uv + caOffset, vec2_splat(0.0), ViewportScale.xy);
    
    float r = texture2D(s_ColorTexture, uvR).x;
    float g = texture2D(s_ColorTexture, uv).y;
    float b = texture2D(s_ColorTexture, uvB).z;
    vec3 sceneColor = vec3(r, g, b);
    
    float unexposeValue = texture2D(s_PreExposureLuminance, vec2_splat(0.5)).x;
    if (TonemapParams0.z > 0.0) {
        sceneColor = UnExposeLighting(sceneColor, unexposeValue);
    }
    
    float averageLuminance = 0.18;
    if (ExposureCompensation.z > 0.5) {
        float targetLuminance = 0.0;
        targetLuminance += texture2D(s_AverageLuminance, vec2_splat(0.5)).x * 0.5;
        targetLuminance += texture2D(s_AverageLuminance, vec2(0.25, 0.25)).x * 0.125;
        targetLuminance += texture2D(s_AverageLuminance, vec2(0.75, 0.25)).x * 0.125;
        targetLuminance += texture2D(s_AverageLuminance, vec2(0.25, 0.75)).x * 0.125;
        targetLuminance += texture2D(s_AverageLuminance, vec2(0.75, 0.75)).x * 0.125;
        
        float dt = ElapsedFrameTime.x;
        float speed = (targetLuminance > unexposeValue) ? 1.5 : 3.0;
        float adaptedLuminance = unexposeValue + (targetLuminance - unexposeValue) * (1.0 - exp(-dt * speed));
        
        averageLuminance = clamp(adaptedLuminance, LuminanceMinMaxAndWhitePointAndMinWhitePoint.x, LuminanceMinMaxAndWhitePointAndMinWhitePoint.y);
    }

    float compensation = ExposureCompensation.y;
    float exposureCurveType = ExposureCompensation.x;
    if ((exposureCurveType > 0.0) && (exposureCurveType < 2.0)) {
        compensation = 1.03 - (2.0 / ((0.43429449 * log(averageLuminance + 1.0)) + 2.0));
    } else if (exposureCurveType > 1.0) {
        float t = (LuminanceMinMaxAndWhitePointAndMinWhitePoint.x == LuminanceMinMaxAndWhitePointAndMinWhitePoint.y) ? 0.5 : ((luminanceToEV100(averageLuminance) - luminanceToEV100(LuminanceMinMaxAndWhitePointAndMinWhitePoint.x)) / (luminanceToEV100(LuminanceMinMaxAndWhitePointAndMinWhitePoint.y) - luminanceToEV100(LuminanceMinMaxAndWhitePointAndMinWhitePoint.x)));
        compensation = texture2D(s_CustomExposureCompensation, vec2(t, 0.5)).x;
    }

    sceneColor = ApplyTemperature(sceneColor);
    sceneColor = ApplyColorGrading(sceneColor, averageLuminance);
    
    vec3 finalColor;
    if (TonemapParams0.y >= 0.5) {
        float whitePoint = max(LuminanceMinMaxAndWhitePointAndMinWhitePoint.z, LuminanceMinMaxAndWhitePointAndMinWhitePoint.w);
        finalColor = ApplyTonemap(sceneColor, averageLuminance, compensation, whitePoint, TonemapParams0.x);
    } else {
        finalColor = sceneColor;
    }
    
    float finalLuminance = luminance(finalColor);
    vec3 e = getGradingVector(ColorGrading_Gamma_Highlights, ColorGrading_Gamma_Shadows, ColorGrading_Gamma_Midtones, 0.18, finalLuminance, vec3_splat(2.2)) * ColorGrading_Misc.w;
    
    finalColor = color_gamma(finalColor, e);
    finalColor = clamp(finalColor, vec3_splat(0.0), vec3_splat(1.0));
    
    float noise = fract(sin(dot(fragInput.texcoord0.xy + vec2_splat(ElapsedFrameTime.x), vec2(12.9898, 78.233))) * 43758.5453);
    finalColor = clamp(finalColor + vec3_splat((noise - 0.5) * 0.03), vec3_splat(0.0), vec3_splat(1.0));
    
    if (RasterizedColorEnabled.x > 0.0) {
        vec4 rasterized = texture2D(s_RasterizedColor, fragInput.texcoord0.xy);
        finalColor = (finalColor * (1.0 - rasterized.w)) + rasterized.xyz;
    }
    
    fragOutput.Color0 = vec4(finalColor, 1.0);
}

void main() {
    FragmentInput fragmentInput;
    FragmentOutput fragmentOutput;
    
    fragmentInput.texcoord0 = v_texcoord0;
    
    Frag(fragmentInput, fragmentOutput);
    
    gl_FragColor = fragmentOutput.Color0;
}
