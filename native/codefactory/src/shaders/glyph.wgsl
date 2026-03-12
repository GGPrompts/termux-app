// Glyph shader: renders textured quads from the glyph atlas.
// Uses instanced rendering with alpha blending for anti-aliased text.

struct Uniforms {
    // Screen dimensions in pixels for NDC conversion
    screen_size: vec2<f32>,
    // Atlas texture dimensions in pixels (for UV computation)
    atlas_size: vec2<f32>,
};

@group(0) @binding(0)
var<uniform> uniforms: Uniforms;

@group(0) @binding(1)
var atlas_texture: texture_2d<f32>;

@group(0) @binding(2)
var atlas_sampler: sampler;

struct InstanceInput {
    // Glyph position in pixels (top-left of glyph bounding box)
    @location(0) pos: vec2<f32>,
    // Glyph size in pixels
    @location(1) size: vec2<f32>,
    // UV rect in atlas: (u_min, v_min, u_max, v_max) in pixels
    @location(2) uv_rect: vec4<f32>,
    // Foreground color (RGBA)
    @location(3) fg_color: vec4<f32>,
    // Flags: bit 0 = underline, bit 1 = strikethrough, bit 2 = inverse (unused in shader for now)
    @location(4) flags: u32,
};

struct VertexOutput {
    @builtin(position) clip_position: vec4<f32>,
    @location(0) uv: vec2<f32>,
    @location(1) color: vec4<f32>,
    @location(2) @interpolate(flat) flags: u32,
    @location(3) local_pos: vec2<f32>,
};

@vertex
fn vs_main(
    @builtin(vertex_index) vertex_index: u32,
    instance: InstanceInput,
) -> VertexOutput {
    var quad_pos: array<vec2<f32>, 6> = array<vec2<f32>, 6>(
        vec2<f32>(0.0, 0.0),
        vec2<f32>(1.0, 0.0),
        vec2<f32>(0.0, 1.0),
        vec2<f32>(0.0, 1.0),
        vec2<f32>(1.0, 0.0),
        vec2<f32>(1.0, 1.0),
    );

    let local_pos = quad_pos[vertex_index];

    // Scale to glyph size and translate to glyph position
    let pixel_pos = instance.pos + local_pos * instance.size;

    // Convert pixel coordinates to NDC
    let ndc = vec2<f32>(
        pixel_pos.x / uniforms.screen_size.x * 2.0 - 1.0,
        1.0 - pixel_pos.y / uniforms.screen_size.y * 2.0,
    );

    // Interpolate UV within the atlas rect (in normalized 0..1 coordinates)
    let uv = vec2<f32>(
        mix(instance.uv_rect.x, instance.uv_rect.z, local_pos.x) / uniforms.atlas_size.x,
        mix(instance.uv_rect.y, instance.uv_rect.w, local_pos.y) / uniforms.atlas_size.y,
    );

    var out: VertexOutput;
    out.clip_position = vec4<f32>(ndc, 0.0, 1.0);
    out.uv = uv;
    out.color = instance.fg_color;
    out.flags = instance.flags;
    out.local_pos = local_pos;
    return out;
}

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    // Sample the glyph alpha from the atlas (stored in R channel)
    let alpha = textureSample(atlas_texture, atlas_sampler, in.uv).r;

    // Apply foreground color with glyph alpha
    var color = vec4<f32>(in.color.rgb, in.color.a * alpha);

    // Underline: draw a line at the bottom of the cell
    let underline_flag = (in.flags & 1u) != 0u;
    if underline_flag && in.local_pos.y > 0.85 && in.local_pos.y < 0.95 {
        color = vec4<f32>(in.color.rgb, in.color.a);
    }

    // Strikethrough: draw a line through the middle
    let strikethrough_flag = (in.flags & 2u) != 0u;
    if strikethrough_flag && in.local_pos.y > 0.45 && in.local_pos.y < 0.55 {
        color = vec4<f32>(in.color.rgb, in.color.a);
    }

    return color;
}
