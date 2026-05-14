$input v_adjacentClouds, v_color0, v_normal, v_tilePosition, v_worldPos

#include <bgfx_shader.sh>

#ifndef DEPTH_ONLY_PASS
// CloudsForwardPBR buffers
SAMPLER2D_AUTOREG(s_BrdfLUT);
SAMPLER2D_AUTOREG(s_PreviousFrameAverageLuminance);
SAMPLER2DARRAY_AUTOREG(s_ScatteringBuffer);
SAMPLER3D_AUTOREG(s_SkyAmbientSamples);
SAMPLERCUBEARRAY_AUTOREG(s_SpecularIBLRecords);

// CloudsForwardPBR uniforms
uniform vec4 AtmosphericScattering;
uniform vec4 AtmosphericScatteringToggles;
uniform vec4 CloudLightingToggles;
uniform vec4 CloudLightingUniforms;
uniform vec4 ConvolutionType;
uniform vec4 DiffuseSpecularEmissiveAmbientTermToggles;
uniform vec4 DirectionalLightSourceDiffuseColorAndIlluminance;
uniform vec4 DirectionalLightSourceWorldSpaceDirection;
uniform vec4 DirectionalLightToggleAndMaxDistanceAndMaxCascadesPerLight;
uniform vec4 DistanceControl;
uniform vec4 FogColor;
uniform vec4 FogSkyBlend;
uniform vec4 IBLParameters;
uniform vec4 LastSpecularIBLIdx;
uniform vec4 MoonColor;
uniform vec4 MoonDir;
uniform vec4 PreExposureEnabled;
uniform vec4 QuantizationParameters;
uniform vec4 SkyAmbientLightColorIntensity;
uniform vec4 SkyHorizonColor;
uniform vec4 SkyProbeUVFadeParameters;
uniform vec4 SkySamplesConfig;
uniform vec4 SkyZenithColor;
uniform vec4 SubsurfaceScatteringContributionAndDiffuseWrapValueAndFalloffScale;
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

/*
    A vanilla cloud shader for the Vibrant Visual (deferred) pipeline for Minecraft Bedrock Edition.
    This shader is responsible for the clouds in the sky.
    CREDITS:
    - The obsfucated shader source code by Veka. Source: https://github.com/veka0/mcbe-shader-codebase/tree/release/obfuscated/materials/LegacyCubemapForwardPBR
    - The creator of the code, Mojang.
    - Modified by, Thallium.

    FEATURES:
    - Softer cloud tints
    - Atmospheric cloud tinting based on sun position and sky color.
*/

#define INV_FOUR_PI   0.079577468335628509521484375
#define INV_PI        0.3183098733425140380859375
#define CLOUD_MAX_RAY 16.0
#define CLOUD_TOP     196.3300018310546875
#define CLOUD_BOTTOM  192.3300018310546875
#define LUMINANCE_R   0.2125999927520751953125
#define LUMINANCE_G   0.715200006961822509765625
#define LUMINANCE_B   0.072200000286102294921875
#define FOG_NEAR      0.89999997615814208984375
#define CLOUD_BLOCK   16.0
#define CLOUD_HEIGHT  (CLOUD_TOP - CLOUD_BOTTOM)

vec3 worldSpaceViewDir(vec3 worldPosition) {
    vec3 cameraPosition = mul(u_invView, vec4(0.0, 0.0, 0.0, 1.0)).xyz;
    return normalize(worldPosition - cameraPosition);
}

float getHorizonBlend(float start, float end, float y) {
    float h = 1.0 - smoothstep(start, end, y);
    return h * h * h;
}

float getRayleighContribution(float VdL, float strength) {
    return strength * (0.75 * ((VdL * VdL) + 1.0));
}

float getMiePhase(float cosAngle, float eccentricity) {
    float g2 = eccentricity * eccentricity;
    float denom = 1.81 - (cosAngle * 1.8);
    return 0.0361 / (denom * sqrt(denom));
}

vec3 calculateSkyColor(vec3 viewDir) {
    vec4 sunColor  = SunColor;
    vec4 moonColor = MoonColor;

    float horizonBlendMin   = FogSkyBlend.x;
    float horizonBlendStart = FogSkyBlend.y;
    float mieStart          = FogSkyBlend.z;
    float horizonBlendMax   = FogSkyBlend.w;

    float endHorizon      = horizonBlendMin - horizonBlendMax;
    float mieStartHorizon = mieStart - horizonBlendMax;

    float sunVdL  = dot(viewDir, SunDir.xyz);
    float moonVdL = dot(viewDir, MoonDir.xyz);

    float horizon    = getHorizonBlend(endHorizon, horizonBlendStart, viewDir.y);
    float mieHorizon = getHorizonBlend(endHorizon, mieStartHorizon, viewDir.y);

    float mieSunVdL  = clamp(pow(max(sunVdL, 0.0),  AtmosphericScattering.w), 0.0, 1.0);
    float mieMoonVdL = clamp(pow(max(moonVdL, 0.0), AtmosphericScattering.w), 0.0, 1.0);

    float sunPhase  = getMiePhase(mieSunVdL,  mieSunVdL);
    float moonPhase = getMiePhase(mieMoonVdL, mieMoonVdL);

    vec3 rayleighColor = mix(SkyZenithColor.xyz, SkyHorizonColor.xyz, vec3_splat(horizon)) * AtmosphericScattering.x;
    vec3 rayleigh = rayleighColor * INV_FOUR_PI * (getRayleighContribution(sunVdL, sunColor.w) + getRayleighContribution(moonVdL, moonColor.w));

    vec3 mieColor = SkyHorizonColor.xyz * mieHorizon;
    vec3 mieSun   = SunColor.xyz  * sunColor.w  * AtmosphericScattering.y * mieSunVdL  * sunPhase;
    vec3 mieMoon  = MoonColor.xyz * moonColor.w * AtmosphericScattering.z * mieMoonVdL * moonPhase;
    vec3 mie = INV_FOUR_PI * mieColor * (mieSun + mieMoon);

    return rayleigh + mie;
}

vec3 applyFog(vec3 color, vec3 fogColor, float fogIntensity) {
    return mix(color, fogColor, vec3_splat(fogIntensity));
}

float computeVerticalRayDistance(vec3 position, vec3 rayDir, float worldOriginY) {
    if (rayDir.y > 0.0) {
        return min(CLOUD_MAX_RAY, (CLOUD_TOP - (position.y - worldOriginY)) / rayDir.y);
    } else if (rayDir.y < 0.0) {
        return min(CLOUD_MAX_RAY, (CLOUD_BOTTOM - (position.y - worldOriginY)) / rayDir.y);
    }
    return CLOUD_MAX_RAY;
}

struct RayAxisResult {
    float dist;
    bool  hasAdjacent;
};

RayAxisResult computeAxisRayDistance(float rayComp, float tileComp, int adjacentMaskPos, int adjacentMaskNeg, int adjacentFlags) {
    RayAxisResult result;
    if (rayComp > 0.0) {
        result.hasAdjacent = (adjacentFlags & adjacentMaskPos) != 0;
        result.dist = (CLOUD_MAX_RAY - tileComp) / rayComp;
    } else if (rayComp < 0.0) {
        result.hasAdjacent = (adjacentFlags & adjacentMaskNeg) != 0;
        result.dist = (-tileComp) / rayComp;
    } else {
        result.hasAdjacent = false;
        result.dist = CLOUD_MAX_RAY;
    }
    return result;
}

float resolveCornerAdjacency(vec3 rayDir, float currentDist, float distX, float distZ, int adjacentFlags) {
    int cornerMask = 0;
    if (rayDir.x > 0.0 && rayDir.z > 0.0) {
        cornerMask = 128;
    } else if (rayDir.x > 0.0 && rayDir.z < 0.0) {
        cornerMask = 4;
    } else if (rayDir.x < 0.0 && rayDir.z > 0.0) {
        cornerMask = 32;
    } else if (rayDir.x < 0.0 && rayDir.z < 0.0) {
        cornerMask = 1;
    }

    if (cornerMask != 0 && (adjacentFlags & cornerMask) == 0) {
        return min(currentDist, max(distX, distZ));
    }
    return currentDist;
}

float computeCloudRayDistance(vec3 position, vec3 rayDir, vec2 tilePos, int adjacentFlags, float worldOriginY) {
    float verticalDist = computeVerticalRayDistance(position, rayDir, worldOriginY);

    RayAxisResult zResult = computeAxisRayDistance(rayDir.z, tilePos.y, 64, 2, adjacentFlags);
    RayAxisResult xResult = computeAxisRayDistance(rayDir.x, tilePos.x, 16, 8, adjacentFlags);

    float rayDist;
    bool  needsCornerCheck;

    if (xResult.dist > zResult.dist) {
        if (!zResult.hasAdjacent) {
            rayDist = min(verticalDist, zResult.dist);
            needsCornerCheck = false;
        } else {
            rayDist = verticalDist;
            needsCornerCheck = true;
        }
    } else if (xResult.dist < zResult.dist) {
        if (!xResult.hasAdjacent) {
            rayDist = min(verticalDist, xResult.dist);
            needsCornerCheck = false;
        } else {
            rayDist = verticalDist;
            needsCornerCheck = true;
        }
    } else {
        rayDist = verticalDist;
        needsCornerCheck = true;
    }

    if (needsCornerCheck) {
        rayDist = resolveCornerAdjacency(rayDir, rayDist, xResult.dist, zResult.dist, adjacentFlags);
    }

    return clamp(rayDist, 0.0, CLOUD_MAX_RAY);
}

float linearToLogDepth(float linearDepth) {
    return log((exp(4.0) - 1.0) * linearDepth + 1.0) / 4.0;
}

vec3 ndcToVolume(vec3 ndc, vec2 nearFar) {
    vec2 uv = 0.5 * (ndc.xy + vec2_splat(1.0));
    vec4 viewSpace = mul(u_invProj, vec4(ndc, 1.0));
    float viewDepth = -viewSpace.z / viewSpace.w;
    float wLinear = (viewDepth - nearFar.x) / (nearFar.y - nearFar.x);
    return vec3(uv, linearToLogDepth(wLinear));
}

vec4 sampleVolume(vec3 uvw) {
    vec3 dims = VolumeDimensions.xyz;
    float depth = uvw.z * dims.z - 0.5;
    int index = clamp(int(depth), 0, int(dims.z) - 2);
    float offset = clamp(depth - float(index), 0.0, 1.0);
    vec4 a = texture2DArrayLod(s_ScatteringBuffer, vec3(uvw.xy, float(index)),     0.0);
    vec4 b = texture2DArrayLod(s_ScatteringBuffer, vec3(uvw.xy, float(index + 1)), 0.0);
    return mix(a, b, vec4_splat(offset));
}

vec3 applyScattering(vec4 sourceExtinction, vec3 color) {
    return sourceExtinction.xyz + sourceExtinction.w * color;
}

float computeMiePhaseFunction(float g, float cosTheta) {
    float g2 = g * g;
    float denom = (1.0 + g2) + (2.0 * g * cosTheta);
    return (INV_FOUR_PI * (1.0 - g2)) / (denom * sqrt(denom));
}

vec3 computeDirectionalScattering(
    vec3 shadedColor,
    vec3 lightDirView,
    vec3 viewPosView,
    vec3 lightIntensity,
    float viewRayDist,
    float lightRayDist
) {
    float cosTheta = dot(lightDirView, -normalize(viewPosView));
    float miePhase = computeMiePhaseFunction(CloudLightingUniforms.y, cosTheta);
    float extinction = exp(-lightRayDist * CloudLightingUniforms.w);
    float edgeFade = 1.0 - smoothstep(0.0, CloudLightingUniforms.x, viewRayDist * 0.5);
    return shadedColor + (lightIntensity * miePhase * extinction * edgeFade);
}

vec3 computeWrappedDiffuse(
    vec3 viewNormal,
    vec3 lightDirView,
    vec3 cloudAlbedo,
    vec3 lightIntensity,
    float viewRayDist
) {
    float wrapValue = SubsurfaceScatteringContributionAndDiffuseWrapValueAndFalloffScale.y;
    float wrapDenom = (1.0 + wrapValue) * (1.0 + wrapValue);

    vec3 diffuseColor = cloudAlbedo * vec3_splat(INV_PI);

    float frontNdL = max((dot(viewNormal, lightDirView) + wrapValue) / wrapDenom, 0.0);
    float backNdL  = max((dot(-viewNormal, lightDirView) + wrapValue) / wrapDenom, 0.0);
    float backFade = 1.0 - smoothstep(0.0, CloudLightingUniforms.x, viewRayDist);

    return ((diffuseColor * frontNdL) + (diffuseColor * backNdL * backFade)) * lightIntensity * DiffuseSpecularEmissiveAmbientTermToggles.x;
}

vec3 computeSpecularIBL(vec3 viewPos, vec3 viewNormal, vec3 worldNormal, vec3 worldPos) {
    vec3 quantizedViewPos;
    if (QuantizationParameters.w > 0.0) {
        quantizedViewPos = mul(u_view, vec4(worldPos, 1.0)).xyz;
    } else {
        quantizedViewPos = viewPos;
    }

    vec3 worldViewDir = worldSpaceViewDir(worldPos);
    vec3 reflectDir = reflect(worldViewDir, worldNormal);

    float iblMipLevel = IBLParameters.y - 1.0;

    int lastIBLIdx = int(LastSpecularIBLIdx.x);
    vec3 prevIBL = textureCubeArrayLod(s_SpecularIBLRecords, vec4(reflectDir, float((lastIBLIdx + 2) % 3)), iblMipLevel).xyz;
    vec3 currIBL = textureCubeArrayLod(s_SpecularIBLRecords, vec4(reflectDir, float(lastIBLIdx)),            iblMipLevel).xyz;
    vec3 iblColor = mix(prevIBL, currIBL, vec3_splat(IBLParameters.w));

    if (PreExposureEnabled.x > 0.0) {
        iblColor *= vec3_splat(301.72412109375);
    }

    vec3 specularIrradiance = iblColor * IBLParameters.z;

    if (DiffuseSpecularEmissiveAmbientTermToggles.w != 0.0) {
        float luminance = dot(specularIrradiance, vec3(LUMINANCE_R, LUMINANCE_G, LUMINANCE_B));
        if (luminance < 0.0) {
            specularIrradiance = vec3_splat(0.0);
        }
    }

    float NdV = clamp(dot(viewNormal, -normalize(quantizedViewPos)), 0.0, 1.0);
    vec2 brdfUV = vec2(NdV, 0.0);
    vec2 brdf = texture2D(s_BrdfLUT, brdfUV).xy;

    vec3 rf0 = vec3_splat(0.039999999105930328369140625);
    return specularIrradiance * (rf0 * brdf.x + vec3_splat(brdf.y));
}

vec3 computeSpecularFallback(vec3 viewPos, vec3 viewNormal, vec3 worldPos) {
    if (DiffuseSpecularEmissiveAmbientTermToggles.w == 0.0) {
        return vec3_splat(0.0);
    }

    vec3 quantizedViewPos;
    if (QuantizationParameters.w > 0.0) {
        quantizedViewPos = mul(u_view, vec4(worldPos, 1.0)).xyz;
    } else {
        quantizedViewPos = viewPos;
    }

    float NdV = clamp(dot(viewNormal, -normalize(quantizedViewPos)), 0.0, 1.0);
    vec2 brdfUV = vec2(NdV, 0.0);
    vec2 brdf = texture2D(s_BrdfLUT, brdfUV).xy;

    vec3 rf0 = vec3_splat(0.039999999105930328369140625);
    return vec3_splat(0.0) * (rf0 * brdf.x + vec3_splat(brdf.y));
}

vec3 preExposeLighting(vec3 color, float averageLuminance) {
    return color * (0.180000007152557373046875 / averageLuminance + 9.9999997473787516355514526367188e-05);
}

bool passesSkyProbeTest(vec3 volumeUVW) {
    if (SkySamplesConfig.x <= 0.5) {
        return true;
    }
    vec3 uvw = volumeUVW;
    uvw.y = 1.0 - uvw.y;
    uvw.z -= SkySamplesConfig.z;
    uvw.z = (exp(4.0 * uvw.z) - 1.0) * 0.0186573602259159088134765625;
    vec2 skyData = texture3DLod(s_SkyAmbientSamples, uvw, 0.0).xy;
    return skyData.y >= SkySamplesConfig.w;
}

vec3 computeRoundedNormal(vec2 tilePos, vec3 worldPos, vec3 normal, float worldOriginY, int adjacentFlags) {
    float localY = worldPos.y - worldOriginY - CLOUD_BOTTOM;
    float hb = CLOUD_BLOCK * 0.5;
    float hh = CLOUD_HEIGHT * 0.5;

    vec2 facePos;
    vec2 faceHalf;
    bool adjNegU = false, adjPosU = false;
    bool adjNegV = false, adjPosV = false;
    
    vec3 tangentU = vec3_splat(0.0);
    vec3 tangentV = vec3_splat(0.0);

    if (abs(normal.y) > 0.5) {
        facePos  = tilePos - vec2_splat(hb);
        faceHalf = vec2_splat(hb);
        adjNegU = (adjacentFlags & 8)  != 0;
        adjPosU = (adjacentFlags & 16) != 0;
        adjNegV = (adjacentFlags & 2)  != 0;
        adjPosV = (adjacentFlags & 64) != 0;
        tangentU = vec3(1.0, 0.0, 0.0);
        tangentV = vec3(0.0, 0.0, 1.0);
    } else if (abs(normal.x) > 0.5) {
        facePos  = vec2(tilePos.y - hb, localY - hh);
        faceHalf = vec2(hb, hh);
        adjNegU = (adjacentFlags & 2)  != 0;
        adjPosU = (adjacentFlags & 64) != 0;
        tangentU = vec3(0.0, 0.0, 1.0);
        tangentV = vec3(0.0, 1.0, 0.0);
    } else {
        facePos  = vec2(tilePos.x - hb, localY - hh);
        faceHalf = vec2(hb, hh);
        adjNegU = (adjacentFlags & 8)  != 0;
        adjPosU = (adjacentFlags & 16) != 0;
        tangentU = vec3(1.0, 0.0, 0.0);
        tangentV = vec3(0.0, 1.0, 0.0);
    }

    if (adjNegU && facePos.x < 0.0) facePos.x = 0.0;
    if (adjPosU && facePos.x > 0.0) facePos.x = 0.0;
    if (adjNegV && facePos.y < 0.0) facePos.y = 0.0;
    if (adjPosV && facePos.y > 0.0) facePos.y = 0.0;

    vec2 uv = facePos / faceHalf;
    vec2 bendMag = sign(uv) * smoothstep(vec2_splat(0.3), vec2_splat(1.0), abs(uv));
    
    return normalize(normal + tangentU * bendMag.x * 0.7 + tangentV * bendMag.y * 0.7);
}

vec3 computeCloudTint(vec3 baseAlbedo) {
    float t = TimeOfDay.x;

    float toSunset  = min(abs(t - 0.25), abs(t - 1.25));
    float toSunrise = min(abs(t - 0.75), abs(t + 0.25));
    float goldenHour = 1.0 - smoothstep(0.0, 0.1, min(toSunset, toSunrise));

    vec3 sunChroma = SunColor.xyz / (length(SunColor.xyz) + 0.001);
    vec3 tint = mix(vec3_splat(1.0), sunChroma, vec3_splat(goldenHour * 0.4));
    tint *= mix(vec3_splat(1.0), SkyHorizonColor.xyz, vec3_splat(goldenHour * 0.2));

    return baseAlbedo * tint;
}
#endif

void main() {
#ifdef DEPTH_ONLY_PASS
    gl_FragColor = vec4_splat(0.0);
#else
    vec4 cloudColor = v_color0;
    float fogIntensity = clamp(max((length(v_worldPos) / DistanceControl.x) - FOG_NEAR, 0.0), 0.0, 1.0);

    vec3 tintedAlbedo = computeCloudTint(v_color0.xyz);

    vec3 skyAmbient = SkyAmbientLightColorIntensity.xyz * SkyAmbientLightColorIntensity.w;
    vec3 shadedColor = (tintedAlbedo * skyAmbient) * DiffuseSpecularEmissiveAmbientTermToggles.w;

    vec3 viewPos = mul(u_view, vec4(v_worldPos, 1.0)).xyz;
    vec4 clipPos = mul(u_proj, vec4(viewPos, 1.0));
    vec3 ndcPos  = clipPos.xyz / vec3_splat(clipPos.w);

    vec3 litColor;

    if (CloudLightingToggles.z != 0.0) {
        vec3 roundedNormal = computeRoundedNormal(v_tilePosition, v_worldPos, v_normal, WorldOrigin.y, v_adjacentClouds);
        vec4 worldNormal4 = vec4(roundedNormal, 0.0);
        vec3 viewNormal = mul(u_view, worldNormal4).xyz;
        vec3 viewDir = normalize(v_worldPos);

        float viewRayDist = computeCloudRayDistance(
            v_worldPos, viewDir, v_tilePosition, v_adjacentClouds, WorldOrigin.y
        );

        vec3 lightDirView = normalize(mul(u_view, DirectionalLightSourceWorldSpaceDirection).xyz);
        vec3 lightIntensity = (DirectionalLightSourceDiffuseColorAndIlluminance.xyz
            * DirectionalLightSourceDiffuseColorAndIlluminance.w)
            * DirectionalLightToggleAndMaxDistanceAndMaxCascadesPerLight.x;

        float t = TimeOfDay.x;
        float toSunset  = min(abs(t - 0.25), abs(t - 1.25));
        float toSunrise = min(abs(t - 0.75), abs(t + 0.25));
        float goldenHour = 1.0 - smoothstep(0.0, 0.1, min(toSunset, toSunrise));

        lightIntensity *= (1.0 + goldenHour * 5.0);

        vec3 scatteredColor;
        if (CloudLightingToggles.y != 0.0) {
            float midpointY = v_worldPos.y + ((viewDir.y * viewRayDist) * 0.5);
            vec2 midpointTile = v_tilePosition + ((viewDir.xz * viewRayDist) * 0.5);
            vec3 lightDirWorld = normalize(DirectionalLightSourceWorldSpaceDirection.xyz);

            float lightRayDist = computeCloudRayDistance(
                vec3(v_worldPos.x, midpointY, v_worldPos.z),
                lightDirWorld, midpointTile, v_adjacentClouds, WorldOrigin.y
            );
            lightRayDist = clamp(lightRayDist, 0.0, CLOUD_MAX_RAY);

            scatteredColor = computeDirectionalScattering(
                shadedColor, lightDirView, viewPos, lightIntensity,
                viewRayDist, lightRayDist
            );
        } else {
            scatteredColor = shadedColor;
        }

        vec3 diffuseColor;
        if (CloudLightingToggles.x != 0.0) {
            diffuseColor = scatteredColor + computeWrappedDiffuse(
                viewNormal, lightDirView, tintedAlbedo,
                lightIntensity, viewRayDist
            );
        } else {
            diffuseColor = scatteredColor;
        }

        vec3 specularColor;
        if (IBLParameters.x != 0.0) {
            specularColor = computeSpecularIBL(viewPos, viewNormal, worldNormal4.xyz, v_worldPos);
        } else {
            specularColor = computeSpecularFallback(viewPos, viewNormal, v_worldPos);
        }

        litColor = diffuseColor + (specularColor * CloudLightingUniforms.z);
    } else {
        litColor = shadedColor;
    }

    vec3 fogAppliedColor;
    if (AtmosphericScatteringToggles.x != 0.0) {
        if (fogIntensity > 0.0) {
            vec3 viewDirection = worldSpaceViewDir(v_worldPos);
            vec3 skyColor = calculateSkyColor(viewDirection);
            fogAppliedColor = applyFog(litColor, skyColor, fogIntensity);
        } else {
            fogAppliedColor = litColor;
        }
    } else {
        fogAppliedColor = applyFog(litColor, FogColor.xyz, fogIntensity);
    }

    vec3 outColor;
    vec3 volumeUVW;
    if (VolumeScatteringEnabledAndPointLightVolumetricsEnabled.x != 0.0) {
        volumeUVW = ndcToVolume(ndcPos, VolumeNearFar.xy);
        vec4 sourceExtinction = sampleVolume(volumeUVW);
        outColor = applyScattering(sourceExtinction, fogAppliedColor);
    } else {
        volumeUVW = vec3_splat(0.0);
        outColor = fogAppliedColor;
    }

    bool skyProbeVisible = passesSkyProbeTest(volumeUVW);
    float finalAlpha;
    vec3 finalColor;
    if (!skyProbeVisible) {
        finalColor = vec3_splat(0.0);
        finalAlpha = 0.0;
    } else {
        finalColor = outColor;
        finalAlpha = cloudColor.w;
    }

#ifdef FORWARD_PBR_TRANSPARENT_SKY_PROBE_PASS
    vec4 reprojClip = mul(u_proj, mul(u_view, vec4(v_worldPos, 1.0)));
    vec2 screenUV = ((reprojClip.xyz / vec3_splat(reprojClip.w)).xy + vec2_splat(1.0)) * vec2_splat(0.5);

    if (PreExposureEnabled.x > 0.0) {
        finalColor *= 0.0033142860047519207000732421875;
    }

    float fadeStart = SkyProbeUVFadeParameters.x;
    float fadeEnd   = SkyProbeUVFadeParameters.y;
    float fadeRange = fadeStart - fadeEnd + 9.9999997473787516355514526367188e-06;
    float fade = (clamp(screenUV.y, fadeEnd, fadeStart) - fadeEnd) / fadeRange;

    gl_FragColor = vec4(finalColor, fade * finalAlpha);
#else
    if (PreExposureEnabled.x > 0.0) {
        float avgLuminance = texture2DLod(s_PreviousFrameAverageLuminance, vec2_splat(0.5), 0.0).x;
        finalColor = preExposeLighting(finalColor, avgLuminance);
    }

    gl_FragColor = vec4(finalColor, finalAlpha);
#endif
#endif
}
