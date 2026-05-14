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
uniform vec4 ElapsedFrameTime;
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
    - The aurora shader by nimitz, modified by me for performance reasons. Source: https://www.shadertoy.com/view/XtGGRt
    - The creator of the code, Mojang.
    - Modified by, Thallium.

    FEATURES:
    - Aurora Borealis shader.

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

vec2 auroraRotate(vec2 v, float c, float s) {
    return vec2(v.x * c - v.y * s, v.x * s + v.y * c);
}

vec2 auroraNoiseRotate(vec2 v) {
    return vec2(v.x * 0.95534 - v.y * 0.29552, v.x * 0.29552 + v.y * 0.95534);
}

float auroraTriWave(float x) {
    return clamp(abs(fract(x) - 0.5), 0.01, 0.49);
}

vec2 auroraTriWave2D(vec2 p) {
    return vec2(auroraTriWave(p.x) + auroraTriWave(p.y), auroraTriWave(p.y) + auroraTriWave(p.x));
}

float auroraIGNoise(vec2 screenPos) {
    vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
    return fract(magic.z * fract(dot(screenPos, magic.xy)));
}

float auroraNoise(vec2 position, float timeC, float timeS) {
    float amplitude = 1.8;
    float shiftScale = 2.5;
    float noiseSum = 0.0;

    float posRotAngle = position.x * 0.06;
    float prc = cos(posRotAngle);
    float prs = sin(posRotAngle);
    position = auroraRotate(position, prc, prs);
    vec2 basePosition = position;

    for (float i = 0.0; i < 3.0; i += 1.0) {
        vec2 domainShift = auroraTriWave2D(basePosition * 1.85) * 0.75;
        domainShift = auroraRotate(domainShift, timeC, timeS);
        position -= domainShift / vec2_splat(shiftScale);

        basePosition *= 1.3;
        shiftScale *= 0.45;
        amplitude *= 0.42;

        position *= 1.21 + (noiseSum - 1.0) * 0.02;

        noiseSum += auroraTriWave(position.x + auroraTriWave(position.y)) * amplitude;
        position = -auroraNoiseRotate(position);
    }

    return clamp(1.0 / pow(noiseSum * 29.0, 1.3), 0.0, 0.55);
}

vec3 auroraBaseColor(float layerIdx, float noiseVal) {
    vec3 colorPhase = vec3_splat(1.0) - vec3(2.15, -0.5, 1.2);
    return (sin(colorPhase + vec3_splat(layerIdx * 0.043)) * 0.5 + 0.5) * noiseVal;
}

vec4 renderAurora(vec3 skyDir, vec2 screenXY) {
    vec4 accum = vec4_splat(0.0);
    vec4 blurred = vec4_splat(0.0);

    float dither = auroraIGNoise(screenXY);
    float timeC = cos(ElapsedFrameTime.x * 0.28);
    float timeS = sin(ElapsedFrameTime.x * 0.28);

    float stride = 2.5;

    for (float i = 0.0; i < 20.0; i += 1.0) {
        float jStep = (i + dither) * stride;

        float layerHeight = 0.8 + pow(jStep, 1.4) * 0.002;
        vec2 sPos = skyDir.xz * (layerHeight / (skyDir.y * 2.0 + 0.4));

        sPos += vec2(sin(sPos.y * 0.6), cos(sPos.x * 0.4)) * 0.8;

        float nVal = auroraNoise(sPos * 1.5, timeC, timeS);
        vec4 stepColor = vec4(auroraBaseColor(jStep, nVal), nVal);

        blurred = mix(blurred, stepColor, vec4_splat(0.6));

        float atten = exp2(-jStep * 0.065 - 2.5) * stride;
        float fadeBot = smoothstep(0.0, 5.0, jStep);

        accum += blurred * atten * fadeBot;
    }

    accum *= clamp(skyDir.y * 15.0 + 0.4, 0.0, 1.0);
    return accum * 1.8;
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

        vec3 skyDir = normalize(v_worldPos);
        if (skyDir.y > 0.0) {
            vec2 screenXY = (v_clipPosition.xy / vec2_splat(v_clipPosition.w)) * 0.5 + vec2_splat(0.5);
            screenXY *= vec2(1280.0, 720.0);

            vec4 auroraColor = smoothstep(vec4_splat(0.0), vec4_splat(1.5), renderAurora(skyDir, screenXY));

            float nightFade = smoothstep(0.3, 0.35, TimeOfDay.x) - smoothstep(0.65, 0.7, TimeOfDay.x);
            auroraColor *= nightFade;

            float horizonFade = smoothstep(0.0, 0.01, abs(skyDir.y)) * 0.1 + 0.9;
            auroraColor *= horizonFade;

            finalColor = finalColor * (1.0 - auroraColor.a) + auroraColor.rgb;
        }
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
