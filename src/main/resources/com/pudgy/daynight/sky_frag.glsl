/*
 * Copyright (c) 2026, Pudgy
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
#version 330

uniform mat4 invViewProj;
uniform vec3 cameraPos;
uniform float elapsedTime;
uniform float nightAmount;
uniform vec3 skyBase;

in vec2 fNdc;
out vec4 FragColor;

// ---------- hashes / noise ----------
float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

vec3 hash33(vec3 p) {
    p = vec3(dot(p, vec3(127.1, 311.7, 74.7)),
             dot(p, vec3(269.5, 183.3, 246.1)),
             dot(p, vec3(113.5, 271.9, 124.6)));
    return fract(sin(p) * 43758.5453123);
}

float hash21(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise2(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1, 0));
    float c = hash21(i + vec2(0, 1));
    float d = hash21(i + vec2(1, 1));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0, amp = 0.5;
    for (int i = 0; i < 5; i++) {
        v += amp * noise2(p);
        p *= 2.0;
        amp *= 0.5;
    }
    return v;
}

// smooth 3D value noise (trilinear) -- no projection singularity on the sphere
float vnoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = hash13(i + vec3(0.0, 0.0, 0.0));
    float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash13(i + vec3(1.0, 1.0, 1.0));
    float nx00 = mix(n000, n100, f.x);
    float nx10 = mix(n010, n110, f.x);
    float nx01 = mix(n001, n101, f.x);
    float nx11 = mix(n011, n111, f.x);
    return mix(mix(nx00, nx10, f.y), mix(nx01, nx11, f.y), f.z);
}

float fbm3(vec3 p) {
    float v = 0.0, amp = 0.5;
    for (int i = 0; i < 4; i++) {
        v += amp * vnoise3(p);
        p *= 2.02;
        amp *= 0.5;
    }
    return v;
}

// ---------- stars ----------
vec3 starField(vec3 dir, float scale, float density, float sharpness, float t, float bright) {
    vec3 cell = floor(dir * scale);
    float h = hash13(cell);
    if (h < 1.0 - density)
        return vec3(0.0);
    vec3 r = hash33(cell + 2.3);
    vec3 sd = normalize((cell + 0.5 + (r - 0.5) * 0.8) / scale);
    float d = distance(dir, sd);
    float core = pow(max(0.0, 1.0 - d / sharpness), 6.0);
    float tw = 0.5 + 0.5 * sin(t * (1.5 + 3.0 * r.x) + r.y * 6.2831);
    tw = pow(tw, 1.6);
    float mag = 0.4 + 0.6 * h;
    vec3 tint = mix(vec3(0.72, 0.82, 1.0), vec3(1.0, 0.90, 0.74), r.z);
    return core * bright * mag * (0.30 + 0.70 * tw) * tint;
}

vec3 brightStars(vec3 dir, float scale, float density, float t) {
    vec3 cell = floor(dir * scale);
    float h = hash13(cell + 9.13);
    if (h < 1.0 - density)
        return vec3(0.0);
    vec3 r = hash33(cell + 5.7);
    vec3 sd = normalize((cell + 0.5 + (r - 0.5) * 0.7) / scale);
    float d = distance(dir, sd);
    float core = pow(max(0.0, 1.0 - d / 0.010), 8.0);
    float tw = 0.6 + 0.4 * sin(t * (1.0 + 2.0 * r.x) + r.y * 6.2831);
    vec3 tint = mix(vec3(0.80, 0.88, 1.0), vec3(1.0, 0.92, 0.80), r.z);
    return core * tw * tint;
}

// ---------- milky way + nebula (3D-noise, no streaks) ----------
vec3 milkyWay(vec3 dir) {
    vec3 mwAxis = normalize(vec3(0.35, 0.45, 0.82));
    float mwd = dot(dir, mwAxis);
    float band = exp(-mwd * mwd * 6.0);

    float cloud = fbm3(dir * 4.0 + 11.0);
    cloud = smoothstep(0.32, 0.85, cloud);
    float dust = fbm3(dir * 7.0 - 5.0);
    float dustLane = smoothstep(0.45, 0.78, dust);   // soft dark dust lanes

    float glow = band * cloud * (1.0 - 0.55 * dustLane);

    vec3 cool = vec3(0.10, 0.14, 0.26);
    vec3 warm = vec3(0.20, 0.17, 0.20);
    vec3 c = mix(cool, warm, cloud);
    return c * glow * 1.10;   // <- overall Milky Way brightness
}

// faint all-sky nebulosity for depth (smooth, sampled directly on direction)
vec3 spaceHaze(vec3 dir) {
    float n = fbm3(dir * 2.2 + 30.0);
    n = smoothstep(0.50, 0.95, n);
    return vec3(0.03, 0.05, 0.10) * n;
}

// ---------- shooting stars ----------
vec3 shootingStars(vec3 dir, float t) {
    vec3 acc = vec3(0.0);
    // tunables: lower the periods (or add slots) for more frequent meteors
    const int SLOTS = 3;
    for (int i = 0; i < SLOTS; i++) {
        float fi = float(i);
        float period = 16.0 + fi * 7.0;
        float seg = floor(t / period + fi * 0.37);
        float ph = fract(t / period + fi * 0.37);
        vec3 r = hash33(vec3(seg, fi, 7.0));

        float appear = 0.15 + 0.6 * r.x;
        float dur = (0.7 + 0.5 * r.y) / period;
        float L = (ph - appear) / dur;
        if (L <= 0.0 || L >= 1.0)
            continue;

        vec3 p0 = normalize(hash33(vec3(seg, 11.0, fi)) - 0.5);
        p0.y = -abs(p0.y);                 // keep in the visible upper sky (OSRS up = -Y)
        p0 = normalize(p0);
        vec3 td = normalize(hash33(vec3(seg, 19.0, fi)) - 0.5);
        vec3 tang = normalize(cross(p0, td));
        vec3 nrm = cross(tang, p0);

        float pf = dot(dir, p0);
        if (pf < 0.2)
            continue;
        float pt = dot(dir, tang);
        float pn = dot(dir, nrm);

        float ang = atan(pt, pf);
        float arc = 0.5;
        float headAng = -0.25 + arc * L;
        float along = headAng - ang;
        float tailLen = 0.14;

        float env = sin(L * 3.14159);
        float bright = 0.0;
        if (along > 0.0 && along < tailLen) {
            float fade = 1.0 - along / tailLen;
            float width = 0.0035 + 0.012 * (along / tailLen);
            bright += exp(-(pn * pn) / (width * width)) * fade * fade;
        }
        float dHead = length(vec2(along, pn));
        bright += exp(-(dHead * dHead) / (0.0060 * 0.0060)) * 1.2;

        acc += vec3(0.80, 0.90, 1.0) * bright * env;
    }
    return acc;
}

void main()
{
    vec4 wp = invViewProj * vec4(fNdc, 1.0, 1.0);
    vec3 worldPos = wp.xyz / wp.w;
    vec3 dir = normalize(worldPos - cameraPos);

    float dayAmount = 1.0 - nightAmount;
    vec3 col = skyBase;

    // ---- daytime clouds ----
    if (dayAmount > 0.001) {
        vec2 cuv = dir.xz / (abs(dir.y) + 0.25);
        cuv = cuv * 0.5 + vec2(elapsedTime * 0.010, elapsedTime * 0.004);
        float n = fbm(cuv);
        float coverage = smoothstep(0.40, 0.68, n);
        coverage *= clamp(-dir.y * 6.0, 0.0, 1.0); // OSRS up is -Y
        col = mix(col, vec3(1.0), coverage * dayAmount);
    }

    // ---- night sky: nebula + deep-space haze + twinkling stars + meteors ----
    if (nightAmount > 0.001) {
        float t = elapsedTime;

        vec3 stars = vec3(0.0);
        stars += starField(dir, 18.0, 0.10, 0.0060, t, 1.00);
        stars += starField(dir, 34.0, 0.08, 0.0040, t, 0.80);
        stars += starField(dir, 60.0, 0.05, 0.0025, t, 0.60);
        stars += brightStars(dir, 9.0, 0.05, t);

        vec3 night = milkyWay(dir) + spaceHaze(dir) + stars;
        night += shootingStars(dir, t);

        col += night * nightAmount;
    }

    FragColor = vec4(col, 1.0);
}
