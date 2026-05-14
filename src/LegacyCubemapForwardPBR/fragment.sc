$input v_clipPosition, v_color0, v_texcoord0, v_worldPos

#include <bgfx_shader.sh>

// LegacyCubemapForwardPBR buffers
SAMPLER2D_AUTOREG(s_MatTexture);
SAMPLER2D_AUTOREG(s_PreviousFrameAverageLuminance);
SAMPLER2DARRAY_AUTOREG(s_ScatteringBuffer);
SAMPLER3D_AUTOREG(s_SkyAmbientSamples);

// LegacyCubemapForwardPBR uniforms
uniform vec4 AmbientLightParams;
uniform vec4 AtmosphericScattering;
uniform vec4 AtmosphericScatteringToggles;
uniform vec4 CloudRenderDistanceAndCloudHeight;
uniform vec4 ColorGrading_OptimizeGammaCorrection;
uniform vec4 DiffuseSpecularEmissiveAmbientTermToggles;
uniform vec4 DirectionalLightSourceDiffuseColorAndIlluminance;
uniform vec4 DirectionalLightToggleAndMaxDistanceAndMaxCascadesPerLight;
uniform vec4 FogSkyBlend;
uniform vec4 MoonColor;
uniform vec4 MoonDir;
uniform vec4 PreExposureEnabled;
uniform vec4 SkyAmbientLightColorIntensity;
uniform vec4 SkyHorizonColor;
uniform vec4 SkyProbeUVFadeParameters;
uniform vec4 SkySamplesConfig;
uniform vec4 SkyZenithColor;
uniform vec4 SkyboxAmbientIlluminance;
uniform vec4 SkyboxParameters;
uniform vec4 SunColor;
uniform vec4 SunDir;
uniform vec4 VolumeDimensions;
uniform vec4 VolumeNearFar;
uniform vec4 VolumeScatteringEnabledAndPointLightVolumetricsEnabled;
uniform vec4 WorldOrigin;

// Custom uniforms that are not used in vanilla, but can be used by shaderpacks for various purposes.
uniform vec4 WeatherID;
uniform vec4 BiomeID;
uniform vec4 CloudHeight;
uniform vec4 Day;
uniform vec4 DimensionID;
uniform vec4 LocalClientID;
uniform vec4 MoonPhase;
uniform vec4 RenderDistance;
uniform vec4 TimeOfDay; // 0.0 is Noon, 0.25 is Sunset, 0.5 is Midnight, 0.75 is Sunrise, 1.0 is back to Noon.
uniform vec4 CameraFacingDirection;
uniform vec4 CameraPosition;
uniform vec4 LastCameraFacingDirection;
uniform vec4 LastCameraPosition;
uniform vec4 SunDirection;
uniform vec4 CloudColor;
uniform vec4 FogColor;

/*
    A cubemap shader for the Vibrant Visual (deferred) pipeline for Minecraft Bedrock Edition.
    This shader is responsible for the cubemap in the sky.
    CREDITS:
    - The obsfucated shader source code by Veka. Source: https://github.com/veka0/mcbe-shader-codebase/tree/release/obfuscated/materials/LegacyCubemapForwardPBR
    - The creator of the code, Mojang.
    - Modified by, Thallium.

    TODO:
    - Improve sky tinting into cubemaps.
*/

float linearToLogDepth(float linearDepth) {
    return log((exp(4.0) - 1.0) * linearDepth + 1.0) / 4.0;
}

vec3 ndcToVolume(vec3 ndc, mat4 inverseProj, vec2 nearFar) {
    vec2 uv = 0.5 * (ndc.xy + vec2_splat(1.0));
    vec4 view = mul(inverseProj, vec4(ndc, 1.0));
    float viewDepth = -view.z / view.w;
    float wLinear = (viewDepth - nearFar.x) / (nearFar.y - nearFar.x);
    return vec3(uv, linearToLogDepth(wLinear));
}

vec3 color_degamma(vec3 clr) {
    if (ColorGrading_OptimizeGammaCorrection.x != 0.0) {
        return pow(max(clr, vec3_splat(0.0)), vec3_splat(2.2));
    } else {
        vec3 lower = clr * vec3_splat(0.07739938);
        vec3 higher = pow((clr + vec3_splat(0.055)) * vec3_splat(0.9478673), vec3_splat(2.4));
        return mix(higher, lower, step(clr, vec3_splat(0.04045)));
    }
}

void main() {
    vec4 sampledColor = texture2D(s_MatTexture, v_texcoord0);
    vec3 color = sampledColor.rgb;
    vec3 degammaColor = color_degamma(color);

    vec3 blockAmbient = AmbientLightParams.xyz * SkyboxAmbientIlluminance.x;
    vec3 skyAmbient = SkyAmbientLightColorIntensity.xyz * SkyAmbientLightColorIntensity.w * SkyboxParameters.x * DiffuseSpecularEmissiveAmbientTermToggles.w;
    vec3 dirLight = DirectionalLightSourceDiffuseColorAndIlluminance.xyz * DirectionalLightSourceDiffuseColorAndIlluminance.w * SkyboxParameters.y * DirectionalLightToggleAndMaxDistanceAndMaxCascadesPerLight.x;
    vec3 baseLighting = blockAmbient + skyAmbient + dirLight;
    
    vec3 skyTint = mix(SkyHorizonColor.xyz, SkyZenithColor.xyz, clamp(normalize(v_worldPos).y + 0.2, 0.0, 1.0));
    
    vec3 totalTint = baseLighting;
    vec3 lightCorrectedColor = degammaColor * totalTint;

    vec3 atmosphericScatteringColor;
    if (SkyboxParameters.z != 0.0 && AtmosphericScatteringToggles.x != 0.0) {
        vec3 cameraPos = mul(u_invView, vec4(0.0, 0.0, 0.0, 1.0)).xyz;
        vec3 viewDir = v_worldPos * ((CloudRenderDistanceAndCloudHeight.y + WorldOrigin.y) / (v_worldPos.y + 0.0001));
        float cloudFade = clamp(max((length(viewDir) / CloudRenderDistanceAndCloudHeight.x) - 0.9, 0.0), 0.0, 1.0);
        
        if (cloudFade > 0.0) {
            vec3 viewDirNorm = normalize(viewDir - cameraPos);
            float horizonBlend = 1.0 - smoothstep(FogSkyBlend.x - FogSkyBlend.w, FogSkyBlend.z - FogSkyBlend.w, viewDirNorm.y);
            float zenithBlend = 1.0 - smoothstep(FogSkyBlend.x - FogSkyBlend.w, FogSkyBlend.y, viewDirNorm.y);
            
            float sunDot = dot(viewDirNorm, SunDir.xyz);
            float moonDot = dot(viewDirNorm, MoonDir.xyz);
            
            float sunScatter = clamp(pow(max(sunDot, 0.0), AtmosphericScattering.w), 0.0, 1.0);
            float moonScatter = clamp(pow(max(moonDot, 0.0), AtmosphericScattering.w), 0.0, 1.0);
            
            float sunMie = 1.81 - (sunScatter * 1.8);
            float moonMie = 1.81 - (moonScatter * 1.8);
            
            vec3 baseScatter = mix(SkyZenithColor.xyz, SkyHorizonColor.xyz, vec3_splat(zenithBlend * zenithBlend * zenithBlend)) * AtmosphericScattering.x * 0.079577468;
            float sunPhase = SunColor.w * (0.75 * (sunDot * sunDot + 1.0));
            float moonPhase = MoonColor.w * (0.75 * (moonDot * moonDot + 1.0));
            
            vec3 horizonScatter = SkyHorizonColor.xyz * (horizonBlend * horizonBlend * horizonBlend) * 0.079577468;
            vec3 sunMieScatter = SunColor.xyz * SunColor.w * AtmosphericScattering.y * sunScatter * (0.0361 / (sunMie * sqrt(sunMie)));
            vec3 moonMieScatter = MoonColor.xyz * MoonColor.w * AtmosphericScattering.z * moonScatter * (0.0361 / (moonMie * sqrt(moonMie)));
            
            vec3 scatterColor = baseScatter * (sunPhase + moonPhase) + horizonScatter * (sunMieScatter + moonMieScatter);
            atmosphericScatteringColor = lightCorrectedColor + scatterColor * cloudFade * 0.3;
        } else {
            atmosphericScatteringColor = lightCorrectedColor;
        }
    } else {
        atmosphericScatteringColor = lightCorrectedColor;
    }

    vec3 volumeUvw = vec3_splat(0.0);
    vec3 volumeScatteringColor;
    if (SkyboxParameters.w != 0.0 && VolumeScatteringEnabledAndPointLightVolumetricsEnabled.x != 0.0) {
        vec3 ndc = v_clipPosition.xyz / vec3_splat(v_clipPosition.w);
        volumeUvw = ndcToVolume(ndc, u_invProj, VolumeNearFar.xy);
        
        float depth = volumeUvw.z * VolumeDimensions.z - 0.5;
        int index = clamp(int(depth), 0, int(VolumeDimensions.z) - 2);
        float offset = clamp(depth - float(index), 0.0, 1.0);
        vec4 a = texture2DArrayLod(s_ScatteringBuffer, vec3(volumeUvw.xy, float(index)), 0.0);
        vec4 b = texture2DArrayLod(s_ScatteringBuffer, vec3(volumeUvw.xy, float(index + 1)), 0.0);
        vec4 sourceExtinction = mix(a, b, offset);
        
        volumeScatteringColor = sourceExtinction.xyz + atmosphericScatteringColor * sourceExtinction.w;
    } else {
        volumeScatteringColor = atmosphericScatteringColor;
    }

    bool shouldRender = true;
    if (SkySamplesConfig.x > 0.5) {
        vec3 sampleUvw = volumeUvw;
        sampleUvw.y = 1.0 - sampleUvw.y;
        sampleUvw.z -= SkySamplesConfig.z;
        sampleUvw.z = (exp(4.0 * sampleUvw.z) - 1.0) * 0.01865736022;
        vec2 skySample = texture3DLod(s_SkyAmbientSamples, sampleUvw, 0.0).xy;
        if (skySample.y < SkySamplesConfig.w) {
            shouldRender = false;
        }
    }
    
    vec3 finalColor = vec3_splat(0.0);
    float finalAlpha = 0.0;
    if (shouldRender) {
        finalColor = volumeScatteringColor;
        finalAlpha = sampledColor.a;
    }

#ifdef FORWARD_PBR_TRANSPARENT_SKY_PROBE_PASS
    vec3 ndc = v_clipPosition.xyz / vec3_splat(v_clipPosition.w);
    vec2 uv = (ndc.xy + vec2_splat(1.0)) * vec2_splat(0.5);
    float fadeStart = SkyProbeUVFadeParameters.x;
    float fadeEnd = SkyProbeUVFadeParameters.y;
    float fadeRange = (fadeStart - fadeEnd) + 1e-5;
    float fade = (clamp(uv.y, fadeEnd, fadeStart) - fadeEnd) / fadeRange;
    finalColor *= fade;
    finalAlpha = max(finalAlpha, SkyProbeUVFadeParameters.z);
#endif

    if (PreExposureEnabled.x > 0.0) {
#ifdef FORWARD_PBR_TRANSPARENT_PASS
        float exposure = texture2D(s_PreviousFrameAverageLuminance, vec2_splat(0.5)).x;
        finalColor *= (0.18 / exposure) + 0.0001;
#endif

#ifdef FORWARD_PBR_TRANSPARENT_SKY_PROBE_PASS
        finalColor *= 0.003314286;
#endif
    }

    gl_FragColor = vec4(finalColor, finalAlpha);
}
