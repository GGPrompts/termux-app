// Background shader: renders colored quads for cell backgrounds.
// Uses instanced rendering -- 6 vertices per quad (2 triangles), one instance per cell.

struct Uniforms {
    // Screen dimensions in pixels for NDC conversion
    screen_size: vec2<f32>,
    _padding: vec2<f32>,
};

@group(0) @binding(0)
var<uniform> uniforms: Uniforms;

struct InstanceInput {
    // Cell position in pixels (top-left corner)
    @location(0) pos: vec2<f32>,
    // Cell size in pixels
    @location(1) size: vec2<f32>,
    // Background color (RGBA)
    @location(2) bg_color: vec4<f32>,
};

struct VertexOutput {
    @builtin(position) clip_position: vec4<f32>,
    @location(0) color: vec4<f32>,
};

// Generate quad vertices from vertex_index (0-5 for two triangles)
@vertex
fn vs_main(
    @builtin(vertex_index) vertex_index: u32,
    instance: InstanceInput,
) -> VertexOutput {
    // Two triangles forming a quad:
    // 0--1    Tri 1: 0,1,2
    // |\ |    Tri 2: 2,1,3
    // 2--3
    var quad_pos: array<vec2<f32>, 6> = array<vec2<f32>, 6>(
        vec2<f32>(0.0, 0.0), // 0 top-left
        vec2<f32>(1.0, 0.0), // 1 top-right
        vec2<f32>(0.0, 1.0), // 2 bottom-left
        vec2<f32>(0.0, 1.0), // 2 bottom-left
        vec2<f32>(1.0, 0.0), // 1 top-right
        vec2<f32>(1.0, 1.0), // 3 bottom-right
    );

    let local_pos = quad_pos[vertex_index];

    // Scale to cell size and translate to cell position
    let pixel_pos = instance.pos + local_pos * instance.size;

    // Convert pixel coordinates to NDC: (0,0) top-left -> (-1,1), (w,h) -> (1,-1)
    let ndc = vec2<f32>(
        pixel_pos.x / uniforms.screen_size.x * 2.0 - 1.0,
        1.0 - pixel_pos.y / uniforms.screen_size.y * 2.0,
    );

    var out: VertexOutput;
    out.clip_position = vec4<f32>(ndc, 0.0, 1.0);
    out.color = instance.bg_color;
    return out;
}

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    return in.color;
}
