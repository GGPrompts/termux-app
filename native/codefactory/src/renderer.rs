//! GPU-accelerated terminal renderer using wgpu.
//!
//! Renders a terminal cell grid with:
//! - Background pass: instanced colored quads per cell
//! - Glyph pass: instanced textured quads from the glyph atlas
//! - Cursor pass: blinking cursor overlay
//! - Selection pass: selection highlight overlay
//!
//! Two render pipelines (background + glyph) with instanced draw calls.
//! Typical frame: 2 draw calls, <1ms on Adreno 730.

use crate::atlas::{GlyphAtlas, GlyphKey};
use crate::colors::{ColorTheme, Rgba};
use crate::grid::{CursorStyle, TermGrid};
use crate::surface::AndroidSurface;
use bytemuck::{Pod, Zeroable};
use jni::objects::JObject;
use jni::JNIEnv;

// ---------------------------------------------------------------------------
// GPU data structures (must match WGSL shader layouts)
// ---------------------------------------------------------------------------

/// Uniform data shared across all instances.
/// Matches the Uniforms struct in both shaders.
#[repr(C)]
#[derive(Debug, Clone, Copy, Pod, Zeroable)]
struct BgUniforms {
    screen_size: [f32; 2],
    _padding: [f32; 2],
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Pod, Zeroable)]
struct GlyphUniforms {
    screen_size: [f32; 2],
    atlas_size: [f32; 2],
}

/// Per-cell instance data for the background pass.
#[repr(C)]
#[derive(Debug, Clone, Copy, Pod, Zeroable)]
struct BgInstance {
    pos: [f32; 2],
    size: [f32; 2],
    bg_color: [f32; 4],
}

/// Per-glyph instance data for the glyph pass.
#[repr(C)]
#[derive(Debug, Clone, Copy, Pod, Zeroable)]
struct GlyphInstance {
    pos: [f32; 2],
    size: [f32; 2],
    uv_rect: [f32; 4],
    fg_color: [f32; 4],
    flags: u32,
    _pad: [u32; 3], // Align to 16 bytes
}

// ---------------------------------------------------------------------------
// Renderer
// ---------------------------------------------------------------------------

/// Holds all wgpu state and rendering resources.
pub struct Renderer {
    /// The Android native window wrapper (must outlive the wgpu surface).
    _android_surface: AndroidSurface,
    /// wgpu surface for presenting frames.
    surface: wgpu::Surface<'static>,
    /// wgpu device for creating GPU resources.
    device: wgpu::Device,
    /// wgpu command queue.
    queue: wgpu::Queue,
    /// Current surface configuration.
    config: wgpu::SurfaceConfiguration,

    // -- Background pipeline --
    bg_pipeline: wgpu::RenderPipeline,
    bg_uniform_buffer: wgpu::Buffer,
    bg_uniform_bind_group: wgpu::BindGroup,
    bg_instance_buffer: wgpu::Buffer,
    bg_instance_count: u32,

    // -- Glyph pipeline --
    glyph_pipeline: wgpu::RenderPipeline,
    glyph_uniform_buffer: wgpu::Buffer,
    glyph_bind_group: wgpu::BindGroup,
    glyph_bind_group_layout: wgpu::BindGroupLayout,
    glyph_instance_buffer: wgpu::Buffer,
    glyph_instance_count: u32,

    // -- Atlas --
    atlas: GlyphAtlas,
    atlas_texture: wgpu::Texture,
    atlas_sampler: wgpu::Sampler,

    // -- Terminal state --
    grid: TermGrid,
    theme: ColorTheme,

    // -- Cursor blink --
    frame_count: u64,
    cursor_blink_rate: u64, // Frames per blink toggle (at 60fps: 30 = 500ms)
}

impl Renderer {
    /// Create a new Renderer from a Java Surface object.
    pub fn new(env: &JNIEnv, java_surface: &JObject) -> Result<Self, String> {
        let android_surface = AndroidSurface::from_java_surface(env, java_surface)?;

        let instance = wgpu::Instance::new(&wgpu::InstanceDescriptor {
            backends: wgpu::Backends::VULKAN,
            ..Default::default()
        });

        log::info!("Renderer: wgpu instance created (Vulkan backend)");

        let surface = unsafe {
            let surface_target = wgpu::SurfaceTargetUnsafe::from_window(&android_surface)
                .map_err(|e| format!("Failed to create surface target: {}", e))?;
            instance
                .create_surface_unsafe(surface_target)
                .map_err(|e| format!("Failed to create wgpu surface: {}", e))?
        };

        let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::HighPerformance,
            compatible_surface: Some(&surface),
            force_fallback_adapter: false,
        }))
        .ok_or_else(|| "Failed to find a suitable GPU adapter".to_string())?;

        log::info!("Renderer: adapter: {:?}", adapter.get_info().name);

        let (device, queue) = pollster::block_on(adapter.request_device(
            &wgpu::DeviceDescriptor {
                label: Some("codefactory-device"),
                required_features: wgpu::Features::empty(),
                required_limits: wgpu::Limits::downlevel_defaults()
                    .using_resolution(adapter.limits()),
                ..Default::default()
            },
            None,
        ))
        .map_err(|e| format!("Failed to create device: {}", e))?;

        let surface_caps = surface.get_capabilities(&adapter);
        let surface_format = surface_caps
            .formats
            .iter()
            .find(|f| f.is_srgb())
            .copied()
            .unwrap_or(surface_caps.formats[0]);

        log::info!("Renderer: surface format: {:?}", surface_format);

        let config = wgpu::SurfaceConfiguration {
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
            format: surface_format,
            width: 1,
            height: 1,
            present_mode: wgpu::PresentMode::Fifo,
            alpha_mode: surface_caps
                .alpha_modes
                .first()
                .copied()
                .unwrap_or(wgpu::CompositeAlphaMode::Auto),
            view_formats: vec![],
            desired_maximum_frame_latency: 2,
        };
        surface.configure(&device, &config);

        // -- Initialize glyph atlas --
        let font_size = 32.0; // Good default for mobile
        let atlas = GlyphAtlas::new(font_size);
        let theme = ColorTheme::default();

        // Create atlas texture
        let atlas_texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("glyph-atlas"),
            size: wgpu::Extent3d {
                width: atlas.atlas_width,
                height: atlas.atlas_height,
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::R8Unorm,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            view_formats: &[],
        });

        // Upload initial atlas data
        queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture: &atlas_texture,
                mip_level: 0,
                origin: wgpu::Origin3d::ZERO,
                aspect: wgpu::TextureAspect::All,
            },
            atlas.pixels(),
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(atlas.atlas_width),
                rows_per_image: Some(atlas.atlas_height),
            },
            wgpu::Extent3d {
                width: atlas.atlas_width,
                height: atlas.atlas_height,
                depth_or_array_layers: 1,
            },
        );

        let atlas_sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("atlas-sampler"),
            address_mode_u: wgpu::AddressMode::ClampToEdge,
            address_mode_v: wgpu::AddressMode::ClampToEdge,
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });

        // -- Background pipeline --
        let bg_shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("bg-shader"),
            source: wgpu::ShaderSource::Wgsl(
                include_str!("shaders/background.wgsl").into(),
            ),
        });

        let bg_uniform_buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("bg-uniforms"),
            size: std::mem::size_of::<BgUniforms>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });

        let bg_bind_group_layout =
            device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                label: Some("bg-bind-group-layout"),
                entries: &[wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::VERTEX,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                }],
            });

        let bg_uniform_bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("bg-bind-group"),
            layout: &bg_bind_group_layout,
            entries: &[wgpu::BindGroupEntry {
                binding: 0,
                resource: bg_uniform_buffer.as_entire_binding(),
            }],
        });

        let bg_pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("bg-pipeline-layout"),
            bind_group_layouts: &[&bg_bind_group_layout],
            push_constant_ranges: &[],
        });

        let bg_pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("bg-pipeline"),
            layout: Some(&bg_pipeline_layout),
            vertex: wgpu::VertexState {
                module: &bg_shader,
                entry_point: Some("vs_main"),
                buffers: &[wgpu::VertexBufferLayout {
                    array_stride: std::mem::size_of::<BgInstance>() as u64,
                    step_mode: wgpu::VertexStepMode::Instance,
                    attributes: &[
                        // pos
                        wgpu::VertexAttribute {
                            offset: 0,
                            shader_location: 0,
                            format: wgpu::VertexFormat::Float32x2,
                        },
                        // size
                        wgpu::VertexAttribute {
                            offset: 8,
                            shader_location: 1,
                            format: wgpu::VertexFormat::Float32x2,
                        },
                        // bg_color
                        wgpu::VertexAttribute {
                            offset: 16,
                            shader_location: 2,
                            format: wgpu::VertexFormat::Float32x4,
                        },
                    ],
                }],
                compilation_options: Default::default(),
            },
            fragment: Some(wgpu::FragmentState {
                module: &bg_shader,
                entry_point: Some("fs_main"),
                targets: &[Some(wgpu::ColorTargetState {
                    format: surface_format,
                    blend: None, // Opaque backgrounds
                    write_mask: wgpu::ColorWrites::ALL,
                })],
                compilation_options: Default::default(),
            }),
            primitive: wgpu::PrimitiveState {
                topology: wgpu::PrimitiveTopology::TriangleList,
                ..Default::default()
            },
            depth_stencil: None,
            multisample: wgpu::MultisampleState::default(),
            multiview: None,
            cache: None,
        });

        // -- Glyph pipeline --
        let glyph_shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("glyph-shader"),
            source: wgpu::ShaderSource::Wgsl(
                include_str!("shaders/glyph.wgsl").into(),
            ),
        });

        let glyph_uniform_buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("glyph-uniforms"),
            size: std::mem::size_of::<GlyphUniforms>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });

        let glyph_bind_group_layout =
            device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                label: Some("glyph-bind-group-layout"),
                entries: &[
                    // Uniforms
                    wgpu::BindGroupLayoutEntry {
                        binding: 0,
                        visibility: wgpu::ShaderStages::VERTEX | wgpu::ShaderStages::FRAGMENT,
                        ty: wgpu::BindingType::Buffer {
                            ty: wgpu::BufferBindingType::Uniform,
                            has_dynamic_offset: false,
                            min_binding_size: None,
                        },
                        count: None,
                    },
                    // Atlas texture
                    wgpu::BindGroupLayoutEntry {
                        binding: 1,
                        visibility: wgpu::ShaderStages::FRAGMENT,
                        ty: wgpu::BindingType::Texture {
                            sample_type: wgpu::TextureSampleType::Float { filterable: true },
                            view_dimension: wgpu::TextureViewDimension::D2,
                            multisampled: false,
                        },
                        count: None,
                    },
                    // Sampler
                    wgpu::BindGroupLayoutEntry {
                        binding: 2,
                        visibility: wgpu::ShaderStages::FRAGMENT,
                        ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                        count: None,
                    },
                ],
            });

        let atlas_view = atlas_texture.create_view(&wgpu::TextureViewDescriptor::default());
        let glyph_bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("glyph-bind-group"),
            layout: &glyph_bind_group_layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: glyph_uniform_buffer.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::TextureView(&atlas_view),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: wgpu::BindingResource::Sampler(&atlas_sampler),
                },
            ],
        });

        let glyph_pipeline_layout =
            device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                label: Some("glyph-pipeline-layout"),
                bind_group_layouts: &[&glyph_bind_group_layout],
                push_constant_ranges: &[],
            });

        let glyph_pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("glyph-pipeline"),
            layout: Some(&glyph_pipeline_layout),
            vertex: wgpu::VertexState {
                module: &glyph_shader,
                entry_point: Some("vs_main"),
                buffers: &[wgpu::VertexBufferLayout {
                    array_stride: std::mem::size_of::<GlyphInstance>() as u64,
                    step_mode: wgpu::VertexStepMode::Instance,
                    attributes: &[
                        // pos
                        wgpu::VertexAttribute {
                            offset: 0,
                            shader_location: 0,
                            format: wgpu::VertexFormat::Float32x2,
                        },
                        // size
                        wgpu::VertexAttribute {
                            offset: 8,
                            shader_location: 1,
                            format: wgpu::VertexFormat::Float32x2,
                        },
                        // uv_rect
                        wgpu::VertexAttribute {
                            offset: 16,
                            shader_location: 2,
                            format: wgpu::VertexFormat::Float32x4,
                        },
                        // fg_color
                        wgpu::VertexAttribute {
                            offset: 32,
                            shader_location: 3,
                            format: wgpu::VertexFormat::Float32x4,
                        },
                        // flags
                        wgpu::VertexAttribute {
                            offset: 48,
                            shader_location: 4,
                            format: wgpu::VertexFormat::Uint32,
                        },
                    ],
                }],
                compilation_options: Default::default(),
            },
            fragment: Some(wgpu::FragmentState {
                module: &glyph_shader,
                entry_point: Some("fs_main"),
                targets: &[Some(wgpu::ColorTargetState {
                    format: surface_format,
                    blend: Some(wgpu::BlendState {
                        color: wgpu::BlendComponent {
                            src_factor: wgpu::BlendFactor::SrcAlpha,
                            dst_factor: wgpu::BlendFactor::OneMinusSrcAlpha,
                            operation: wgpu::BlendOperation::Add,
                        },
                        alpha: wgpu::BlendComponent {
                            src_factor: wgpu::BlendFactor::One,
                            dst_factor: wgpu::BlendFactor::OneMinusSrcAlpha,
                            operation: wgpu::BlendOperation::Add,
                        },
                    }),
                    write_mask: wgpu::ColorWrites::ALL,
                })],
                compilation_options: Default::default(),
            }),
            primitive: wgpu::PrimitiveState {
                topology: wgpu::PrimitiveTopology::TriangleList,
                ..Default::default()
            },
            depth_stencil: None,
            multisample: wgpu::MultisampleState::default(),
            multiview: None,
            cache: None,
        });

        // Create initial instance buffers (will be resized on first render)
        let max_cells = 200 * 60; // Max grid size
        let bg_instance_buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("bg-instances"),
            size: (max_cells * std::mem::size_of::<BgInstance>()) as u64,
            usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });

        let glyph_instance_buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("glyph-instances"),
            // Extra space for cursor + selection overlays
            size: ((max_cells + 1000) * std::mem::size_of::<GlyphInstance>()) as u64,
            usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });

        // Create demo grid
        let grid = TermGrid::demo(80, 24);

        log::info!(
            "Renderer: initialization complete, atlas={}x{}, cell={}x{}",
            atlas.atlas_width,
            atlas.atlas_height,
            atlas.cell_width,
            atlas.cell_height,
        );

        Ok(Self {
            _android_surface: android_surface,
            surface,
            device,
            queue,
            config,
            bg_pipeline,
            bg_uniform_buffer,
            bg_uniform_bind_group,
            bg_instance_buffer,
            bg_instance_count: 0,
            glyph_pipeline,
            glyph_uniform_buffer,
            glyph_bind_group,
            glyph_bind_group_layout,
            glyph_instance_buffer,
            glyph_instance_count: 0,
            atlas,
            atlas_texture,
            atlas_sampler,
            grid,
            theme,
            frame_count: 0,
            cursor_blink_rate: 30, // ~500ms at 60fps
        })
    }

    /// Reconfigure the surface with new dimensions.
    ///
    /// Only reconfigures the wgpu surface. The terminal grid is managed by
    /// the pipeline (via `set_grid`). If no pipeline is active, creates a
    /// demo grid for visual testing.
    pub fn resize(&mut self, width: u32, height: u32) {
        if width == 0 || height == 0 {
            log::warn!("Renderer::resize: ignoring zero-size ({}x{})", width, height);
            return;
        }

        log::info!("Renderer::resize: {}x{}", width, height);
        self.config.width = width;
        self.config.height = height;
        self.surface.configure(&self.device, &self.config);

        // Recompute grid dimensions for fallback demo grid.
        // When the pipeline is active, it calls set_grid() with the real
        // terminal content at the correct dimensions.
        let cols = (width as f32 / self.atlas.cell_width).floor() as usize;
        let rows = (height as f32 / self.atlas.cell_height).floor() as usize;
        let cols = cols.max(1);
        let rows = rows.max(1);

        log::info!("Renderer::resize: grid {}x{}", cols, rows);
        // Only set demo grid if current grid dimensions don't match.
        // When pipeline is active, it will overwrite via set_grid().
        if self.grid.cols != cols || self.grid.rows != rows {
            self.grid = TermGrid::demo(cols, rows);
        }
    }

    /// Build instance data from the terminal grid.
    fn build_instances(&mut self) -> (Vec<BgInstance>, Vec<GlyphInstance>) {
        let cell_w = self.atlas.cell_width;
        let cell_h = self.atlas.cell_height;
        let cols = self.grid.cols;
        let rows = self.grid.rows;

        let mut bg_instances = Vec::with_capacity(cols * rows);
        let mut glyph_instances = Vec::with_capacity(cols * rows);

        for row in 0..rows {
            for col in 0..cols {
                let cell = self.grid.cell(col, row);
                let px = col as f32 * cell_w;
                let py = row as f32 * cell_h;

                // Handle inverse video: swap fg/bg
                let (fg, bg) = if cell.style.flags.inverse {
                    (cell.style.bg, cell.style.fg)
                } else {
                    (cell.style.fg, cell.style.bg)
                };

                // Dim: reduce foreground alpha
                let fg = if cell.style.flags.dim {
                    Rgba::new(fg.r, fg.g, fg.b, fg.a * 0.5)
                } else {
                    fg
                };

                // Background instance (always emit -- needed for opaque fill)
                bg_instances.push(BgInstance {
                    pos: [px, py],
                    size: [cell_w, cell_h],
                    bg_color: bg.to_array(),
                });

                // Glyph instance (skip spaces and empty cells)
                if cell.ch != ' ' && cell.ch != '\0' {
                    let key = GlyphKey {
                        ch: cell.ch,
                        bold: cell.style.flags.bold,
                        italic: cell.style.flags.italic,
                    };
                    let entry = self.atlas.get_or_insert(key);

                    if entry.width > 0 && entry.height > 0 {
                        let gx = px + entry.offset_x;
                        let gy = py + entry.offset_y;

                        glyph_instances.push(GlyphInstance {
                            pos: [gx, gy],
                            size: [entry.width as f32, entry.height as f32],
                            uv_rect: [
                                entry.x as f32,
                                entry.y as f32,
                                (entry.x + entry.width) as f32,
                                (entry.y + entry.height) as f32,
                            ],
                            fg_color: fg.to_array(),
                            flags: cell.style.flags.to_gpu_flags(),
                            _pad: [0; 3],
                        });
                    }
                }

                // If this cell has underline or strikethrough but is a space,
                // we still need to draw the decoration
                if cell.ch == ' '
                    && (cell.style.flags.underline || cell.style.flags.strikethrough)
                {
                    // Use a minimal glyph instance spanning the cell for decorations only
                    glyph_instances.push(GlyphInstance {
                        pos: [px, py],
                        size: [cell_w, cell_h],
                        uv_rect: [0.0, 0.0, 0.0, 0.0], // No atlas sampling (alpha = 0)
                        fg_color: fg.to_array(),
                        flags: cell.style.flags.to_gpu_flags(),
                        _pad: [0; 3],
                    });
                }
            }
        }

        // -- Selection overlay --
        if let Some(sel) = &self.grid.selection {
            for row in sel.start_row..=sel.end_row {
                if row >= rows {
                    continue;
                }
                let start_col = if row == sel.start_row {
                    sel.start_col
                } else {
                    0
                };
                let end_col = if row == sel.end_row {
                    sel.end_col
                } else {
                    cols - 1
                };
                for col in start_col..=end_col.min(cols - 1) {
                    let px = col as f32 * cell_w;
                    let py = row as f32 * cell_h;
                    bg_instances.push(BgInstance {
                        pos: [px, py],
                        size: [cell_w, cell_h],
                        bg_color: [sel.color.r, sel.color.g, sel.color.b, 0.4],
                    });
                }
            }
        }

        // -- Cursor overlay --
        let cursor = &self.grid.cursor;
        let cursor_visible =
            cursor.visible && (self.frame_count / self.cursor_blink_rate) % 2 == 0;

        if cursor_visible && cursor.col < cols && cursor.row < rows {
            let px = cursor.col as f32 * cell_w;
            let py = cursor.row as f32 * cell_h;

            match cursor.style {
                CursorStyle::Block => {
                    // Block cursor: colored background quad
                    bg_instances.push(BgInstance {
                        pos: [px, py],
                        size: [cell_w, cell_h],
                        bg_color: cursor.color.to_array(),
                    });
                }
                CursorStyle::Underline => {
                    // Underline cursor: thin line at bottom
                    let underline_h = 2.0f32.max(cell_h * 0.08);
                    bg_instances.push(BgInstance {
                        pos: [px, py + cell_h - underline_h],
                        size: [cell_w, underline_h],
                        bg_color: cursor.color.to_array(),
                    });
                }
                CursorStyle::Bar => {
                    // Bar cursor: thin line at left
                    let bar_w = 2.0f32.max(cell_w * 0.08);
                    bg_instances.push(BgInstance {
                        pos: [px, py],
                        size: [bar_w, cell_h],
                        bg_color: cursor.color.to_array(),
                    });
                }
            }
        }

        (bg_instances, glyph_instances)
    }

    /// Render a single frame.
    ///
    /// Handles wgpu surface errors gracefully:
    /// - `Lost` / `Outdated`: reconfigures the surface and retries once.
    ///   This happens during surface recreation on Android (backgrounding,
    ///   rotation, split-screen transitions).
    /// - `OutOfMemory`: logs and returns error (unrecoverable).
    /// - `Timeout`: logs and skips the frame (transient).
    pub fn render(&mut self) -> Result<(), String> {
        self.frame_count += 1;

        // Build instance data from the grid
        let (bg_instances, glyph_instances) = self.build_instances();

        // If the atlas was modified (new glyphs rasterized), re-upload the texture
        if self.atlas.dirty {
            self.upload_atlas();
            self.atlas.dirty = false;
        }

        // Upload uniform data
        let bg_uniforms = BgUniforms {
            screen_size: [self.config.width as f32, self.config.height as f32],
            _padding: [0.0; 2],
        };
        self.queue.write_buffer(
            &self.bg_uniform_buffer,
            0,
            bytemuck::bytes_of(&bg_uniforms),
        );

        let glyph_uniforms = GlyphUniforms {
            screen_size: [self.config.width as f32, self.config.height as f32],
            atlas_size: [self.atlas.atlas_width as f32, self.atlas.atlas_height as f32],
        };
        self.queue.write_buffer(
            &self.glyph_uniform_buffer,
            0,
            bytemuck::bytes_of(&glyph_uniforms),
        );

        // Upload instance buffers
        self.bg_instance_count = bg_instances.len() as u32;
        if !bg_instances.is_empty() {
            self.queue.write_buffer(
                &self.bg_instance_buffer,
                0,
                bytemuck::cast_slice(&bg_instances),
            );
        }

        self.glyph_instance_count = glyph_instances.len() as u32;
        if !glyph_instances.is_empty() {
            self.queue.write_buffer(
                &self.glyph_instance_buffer,
                0,
                bytemuck::cast_slice(&glyph_instances),
            );
        }

        // Get surface texture, handling recoverable errors
        let output = match self.surface.get_current_texture() {
            Ok(output) => {
                // Check for suboptimal surface (not an error, but worth noting)
                if output.suboptimal {
                    log::debug!("Renderer: surface texture is suboptimal, reconfiguring");
                    self.surface.configure(&self.device, &self.config);
                }
                output
            }
            Err(wgpu::SurfaceError::Lost | wgpu::SurfaceError::Outdated) => {
                // Surface was lost or invalidated. This commonly happens on Android
                // during activity lifecycle transitions (background/foreground,
                // rotation, split-screen). Reconfigure and retry once.
                log::warn!(
                    "Renderer: surface lost/outdated, reconfiguring ({}x{})",
                    self.config.width,
                    self.config.height
                );
                self.surface.configure(&self.device, &self.config);
                match self.surface.get_current_texture() {
                    Ok(output) => output,
                    Err(e) => {
                        // Second failure means the surface is truly gone.
                        // The Java side should call nativeSurfaceDestroyed soon.
                        return Err(format!(
                            "Surface irrecoverable after reconfigure: {}",
                            e
                        ));
                    }
                }
            }
            Err(wgpu::SurfaceError::Timeout) => {
                // GPU is busy, skip this frame. Not an error worth propagating.
                log::debug!("Renderer: surface texture timeout, skipping frame");
                return Ok(());
            }
            Err(e) => {
                return Err(format!("Failed to get surface texture: {}", e));
            }
        };

        let view = output
            .texture
            .create_view(&wgpu::TextureViewDescriptor::default());

        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("frame-encoder"),
            });

        {
            // Clear pass + background rendering
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("bg-pass"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &view,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(wgpu::Color {
                            r: self.theme.bg.r as f64,
                            g: self.theme.bg.g as f64,
                            b: self.theme.bg.b as f64,
                            a: 1.0,
                        }),
                        store: wgpu::StoreOp::Store,
                    },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
            });

            if self.bg_instance_count > 0 {
                pass.set_pipeline(&self.bg_pipeline);
                pass.set_bind_group(0, &self.bg_uniform_bind_group, &[]);
                pass.set_vertex_buffer(0, self.bg_instance_buffer.slice(..));
                pass.draw(0..6, 0..self.bg_instance_count);
            }
        }

        {
            // Glyph rendering (alpha blended on top of backgrounds)
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("glyph-pass"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &view,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Load, // Keep backgrounds
                        store: wgpu::StoreOp::Store,
                    },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
            });

            if self.glyph_instance_count > 0 {
                pass.set_pipeline(&self.glyph_pipeline);
                pass.set_bind_group(0, &self.glyph_bind_group, &[]);
                pass.set_vertex_buffer(0, self.glyph_instance_buffer.slice(..));
                pass.draw(0..6, 0..self.glyph_instance_count);
            }
        }

        self.queue.submit(std::iter::once(encoder.finish()));
        output.present();

        if self.frame_count % 120 == 0 {
            log::debug!(
                "Renderer: frame {}, bg={}, glyphs={}",
                self.frame_count,
                self.bg_instance_count,
                self.glyph_instance_count,
            );
        }

        Ok(())
    }

    /// Re-upload the entire atlas texture to the GPU.
    /// Called when new glyphs were rasterized since the last frame.
    fn upload_atlas(&mut self) {
        // If the atlas grew, we need to recreate the texture and bind group
        let current_size = self.atlas_texture.size();
        if current_size.width != self.atlas.atlas_width
            || current_size.height != self.atlas.atlas_height
        {
            log::info!(
                "Renderer: recreating atlas texture {}x{} -> {}x{}",
                current_size.width,
                current_size.height,
                self.atlas.atlas_width,
                self.atlas.atlas_height,
            );

            self.atlas_texture = self.device.create_texture(&wgpu::TextureDescriptor {
                label: Some("glyph-atlas"),
                size: wgpu::Extent3d {
                    width: self.atlas.atlas_width,
                    height: self.atlas.atlas_height,
                    depth_or_array_layers: 1,
                },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: wgpu::TextureFormat::R8Unorm,
                usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
                view_formats: &[],
            });

            // Recreate bind group with new texture
            let atlas_view = self
                .atlas_texture
                .create_view(&wgpu::TextureViewDescriptor::default());
            self.glyph_bind_group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("glyph-bind-group"),
                layout: &self.glyph_bind_group_layout,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: self.glyph_uniform_buffer.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 1,
                        resource: wgpu::BindingResource::TextureView(&atlas_view),
                    },
                    wgpu::BindGroupEntry {
                        binding: 2,
                        resource: wgpu::BindingResource::Sampler(&self.atlas_sampler),
                    },
                ],
            });
        }

        // Upload pixel data
        self.queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture: &self.atlas_texture,
                mip_level: 0,
                origin: wgpu::Origin3d::ZERO,
                aspect: wgpu::TextureAspect::All,
            },
            self.atlas.pixels(),
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(self.atlas.atlas_width),
                rows_per_image: Some(self.atlas.atlas_height),
            },
            wgpu::Extent3d {
                width: self.atlas.atlas_width,
                height: self.atlas.atlas_height,
                depth_or_array_layers: 1,
            },
        );
    }

    /// Update the terminal grid with new content.
    /// Called from the PTY-to-renderer pipeline each frame (when dirty).
    pub fn set_grid(&mut self, grid: TermGrid) {
        self.grid = grid;
    }

    /// Get the current grid dimensions (cols, rows).
    pub fn grid_size(&self) -> (usize, usize) {
        (self.grid.cols, self.grid.rows)
    }

    /// Get cell dimensions in pixels.
    pub fn cell_size(&self) -> (f32, f32) {
        (self.atlas.cell_width, self.atlas.cell_height)
    }
}
