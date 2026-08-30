package com.depthmaker.app.toon

private const val HEADER = """
precision mediump float;
varying vec2 vTex;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uStrength;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

// Sobel magnitude on luminance. Used by every outline-drawing filter.
float edge() {
    float tl = luma(texture2D(uTex, vTex + uTexel * vec2(-1.0, -1.0)).rgb);
    float t  = luma(texture2D(uTex, vTex + uTexel * vec2( 0.0, -1.0)).rgb);
    float tr = luma(texture2D(uTex, vTex + uTexel * vec2( 1.0, -1.0)).rgb);
    float l  = luma(texture2D(uTex, vTex + uTexel * vec2(-1.0,  0.0)).rgb);
    float r  = luma(texture2D(uTex, vTex + uTexel * vec2( 1.0,  0.0)).rgb);
    float bl = luma(texture2D(uTex, vTex + uTexel * vec2(-1.0,  1.0)).rgb);
    float b  = luma(texture2D(uTex, vTex + uTexel * vec2( 0.0,  1.0)).rgb);
    float br = luma(texture2D(uTex, vTex + uTexel * vec2( 1.0,  1.0)).rgb);
    float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
    float gy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
    return sqrt(gx * gx + gy * gy);
}

// Edge-aware 3x3 smoothing: a real bilateral is separable and expensive, and at
// 720p60 the cheap version is visually indistinguishable once the result is
// posterised anyway.
vec3 smoothColor() {
    vec3 center = texture2D(uTex, vTex).rgb;
    vec3 sum = center;
    float weight = 1.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            if (x == 0 && y == 0) continue;
            vec3 s = texture2D(uTex, vTex + uTexel * vec2(float(x), float(y))).rgb;
            float w = 1.0 - min(1.0, distance(s, center) * 3.0);
            sum += s * w;
            weight += w;
        }
    }
    return sum / weight;
}

vec3 posterize(vec3 c, float levels) {
    return floor(c * levels + 0.5) / levels;
}
"""

private const val PASSTHROUGH = """
precision mediump float;
varying vec2 vTex;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uStrength;
void main() { gl_FragColor = texture2D(uTex, vTex); }
"""

private const val CARTOON_SHADER = HEADER + """
void main() {
    vec3 src = texture2D(uTex, vTex).rgb;
    vec3 flat_ = posterize(smoothColor(), mix(12.0, 4.0, uStrength));
    // Boost saturation a little; posterising alone reads as washed out.
    float l = luma(flat_);
    flat_ = clamp(mix(vec3(l), flat_, 1.0 + 0.6 * uStrength), 0.0, 1.0);
    float ink = 1.0 - smoothstep(0.15, 0.15 + 0.5 * (1.0 - uStrength), edge());
    gl_FragColor = vec4(mix(src, flat_ * ink, uStrength), 1.0);
}
"""

private const val COMIC_SHADER = HEADER + """
void main() {
    vec3 src = texture2D(uTex, vTex).rgb;
    vec3 flat_ = posterize(smoothColor(), 4.0);
    float l = luma(flat_);

    // Halftone: a dot grid in screen space, sized by local darkness. Screen
    // space, not texture space, so the dots stay a constant physical size
    // instead of stretching with the source aspect.
    vec2 grid = vTex / uTexel / mix(14.0, 6.0, uStrength);
    vec2 cell = fract(grid) - 0.5;
    float dot_ = smoothstep(0.30, 0.34, length(cell) * (0.5 + l));
    float shade = mix(1.0, dot_, (1.0 - l) * uStrength);

    float ink = 1.0 - smoothstep(0.20, 0.55, edge());
    vec3 comic = flat_ * shade * ink;
    gl_FragColor = vec4(mix(src, comic, uStrength), 1.0);
}
"""

private const val SKETCH_SHADER = HEADER + """
void main() {
    vec3 src = texture2D(uTex, vTex).rgb;
    float e = edge();
    // Pencil: paper white, graphite where the gradient is, plus a light wash of
    // the original luminance so flat areas are not dead white.
    float graphite = 1.0 - smoothstep(0.05, 0.35, e);
    float wash = mix(1.0, 0.55 + 0.45 * luma(src), 0.5 * uStrength);
    float v = clamp(graphite * wash, 0.0, 1.0);
    gl_FragColor = vec4(mix(src, vec3(v), uStrength), 1.0);
}
"""

private const val POP_ART_SHADER = HEADER + """
// Four flat bands mapped onto a hot Warhol-ish palette.
vec3 palette(float t) {
    if (t < 0.25) return vec3(0.13, 0.09, 0.35);
    if (t < 0.50) return vec3(0.92, 0.13, 0.45);
    if (t < 0.75) return vec3(1.00, 0.62, 0.11);
    return vec3(1.00, 0.95, 0.62);
}

void main() {
    vec3 src = texture2D(uTex, vTex).rgb;
    float l = luma(smoothColor());
    vec3 pop = palette(l);
    float ink = 1.0 - smoothstep(0.18, 0.50, edge());
    gl_FragColor = vec4(mix(src, pop * ink, uStrength), 1.0);
}
"""

/**
 * The filter catalogue. Each entry is one fragment shader run over the
 * already-deinterlaced, already-scaled RGBA texture produced by pass 1, so the
 * shaders never touch the external-OES sampler or the SurfaceTexture matrix.
 *
 * Uniform contract, identical for every filter:
 *   uTex      sampler2D  pass-1 output
 *   uTexel    vec2       1/width, 1/height — neighbour offsets
 *   uStrength float      0..1, the user's slider
 */

enum class ToonFilter(val label: String, val fragmentShader: String) {

    NONE("Original", PASSTHROUGH),
    CARTOON("Cartoon", CARTOON_SHADER),
    COMIC("Comic", COMIC_SHADER),
    SKETCH("Sketch", SKETCH_SHADER),
    POP_ART("Pop Art", POP_ART_SHADER);

    companion object {
        fun fromId(id: String?): ToonFilter =
            entries.firstOrNull { it.name == id } ?: CARTOON
    }
}
