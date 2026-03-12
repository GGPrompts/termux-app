//! Glyph atlas: rasterizes glyphs on CPU using fontdue, packs them into a
//! dynamic texture atlas for GPU sampling.
//!
//! Architecture:
//! - `GlyphKey` = (char, bold, italic) -- each unique combo gets its own atlas entry
//! - Row-based packing: fill rows left-to-right, advance to next row when full
//! - HashMap<GlyphKey, AtlasEntry> for O(1) lookup
//! - Atlas starts at 2048x2048, grows by doubling height if needed
//! - Pre-rasterizes ASCII 0x20..=0x7E on init for cache warmth
//!
//! Font stack (fallback order):
//! 1. JetBrains Mono Nerd Font (primary) -- covers ASCII, box drawing, powerline,
//!    devicons, weather icons, Material Design, Font Awesome, Octicons, etc.
//! 2. Missing glyphs produce a zero-size atlas entry (blank cell) rather than tofu.
//!    Color emoji is not supported (atlas is R8Unorm / grayscale).

use std::collections::HashMap;

/// Identifies a unique glyph in the atlas.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct GlyphKey {
    pub ch: char,
    pub bold: bool,
    pub italic: bool,
}

/// Location and metrics of a rasterized glyph in the atlas texture.
#[derive(Debug, Clone, Copy)]
pub struct AtlasEntry {
    /// X offset in atlas texture (pixels)
    pub x: u32,
    /// Y offset in atlas texture (pixels)
    pub y: u32,
    /// Width of the rasterized glyph bitmap (pixels)
    pub width: u32,
    /// Height of the rasterized glyph bitmap (pixels)
    pub height: u32,
    /// Horizontal offset from cell origin to glyph bitmap left edge
    pub offset_x: f32,
    /// Vertical offset from cell top to glyph bitmap top edge
    pub offset_y: f32,
}

/// The glyph atlas: CPU-side rasterization cache + GPU texture.
pub struct GlyphAtlas {
    /// Regular weight font
    font_regular: fontdue::Font,
    /// Bold weight font
    font_bold: fontdue::Font,
    /// Font size in pixels
    font_size: f32,
    /// Cell width in pixels (advance width of a monospace character)
    pub cell_width: f32,
    /// Cell height in pixels (line height)
    pub cell_height: f32,
    /// Baseline offset from cell top
    pub baseline: f32,
    /// Atlas texture width
    pub atlas_width: u32,
    /// Atlas texture height
    pub atlas_height: u32,
    /// CPU-side pixel buffer (R8, single channel alpha)
    pixels: Vec<u8>,
    /// Current packing cursor: next x position in current row
    cursor_x: u32,
    /// Current packing cursor: y position of current row
    cursor_y: u32,
    /// Height of the tallest glyph in the current row
    row_height: u32,
    /// Padding between glyphs (prevents texture bleeding)
    padding: u32,
    /// Lookup from GlyphKey to atlas entry
    entries: HashMap<GlyphKey, AtlasEntry>,
    /// Whether the GPU texture needs re-uploading
    pub dirty: bool,
}

impl GlyphAtlas {
    /// Create a new glyph atlas with the given font size.
    ///
    /// Loads the bundled JetBrains Mono Nerd Font (Regular and Bold).
    /// Pre-rasterizes ASCII printable range (0x20..=0x7E) for both regular and bold,
    /// plus box drawing, block elements, and common Nerd Font / powerline glyphs.
    pub fn new(font_size: f32) -> Self {
        let font_data_regular =
            include_bytes!("../assets/fonts/JetBrainsMonoNerdFont-Regular.ttf");
        let font_data_bold =
            include_bytes!("../assets/fonts/JetBrainsMonoNerdFont-Bold.ttf");

        let settings = fontdue::FontSettings {
            scale: font_size,
            ..fontdue::FontSettings::default()
        };

        let font_regular =
            fontdue::Font::from_bytes(font_data_regular.as_slice(), settings)
                .expect("Failed to load JetBrains Mono Nerd Font Regular");
        let font_bold =
            fontdue::Font::from_bytes(font_data_bold.as_slice(), settings)
                .expect("Failed to load JetBrains Mono Nerd Font Bold");

        log::info!(
            "GlyphAtlas: loaded Nerd Font Regular ({} glyphs), Bold ({} glyphs)",
            font_regular.glyph_count(),
            font_bold.glyph_count()
        );

        // Compute cell metrics from the regular font
        let metrics_m = font_regular.metrics('M', font_size);
        let metrics_line = font_regular.horizontal_line_metrics(font_size);

        // Cell width = advance width of 'M' (monospace, so all chars are the same)
        let cell_width = metrics_m.advance_width.ceil();

        // Cell height and baseline from line metrics
        let (cell_height, baseline) = if let Some(lm) = metrics_line {
            let ascent = lm.ascent;
            let descent = lm.descent; // negative value
            let line_gap = lm.line_gap;
            let height = (ascent - descent + line_gap).ceil();
            (height, ascent.ceil())
        } else {
            // Fallback: use font_size * 1.2 as line height
            (font_size * 1.2, font_size * 0.8)
        };

        // Start with a larger atlas (2048x2048) since Nerd Font has many more
        // glyphs to pre-rasterize than plain JetBrains Mono
        let atlas_width = 2048u32;
        let atlas_height = 2048u32;
        let pixels = vec![0u8; (atlas_width * atlas_height) as usize];

        let mut atlas = Self {
            font_regular,
            font_bold,
            font_size,
            cell_width,
            cell_height,
            baseline,
            atlas_width,
            atlas_height,
            pixels,
            cursor_x: 1, // Start at 1 to leave a 1px border
            cursor_y: 1,
            row_height: 0,
            padding: 2,
            entries: HashMap::with_capacity(512),
            dirty: true,
        };

        // Pre-rasterize ASCII printable range for regular and bold
        for ch in ' '..='~' {
            atlas.rasterize(GlyphKey {
                ch,
                bold: false,
                italic: false,
            });
            atlas.rasterize(GlyphKey {
                ch,
                bold: true,
                italic: false,
            });
        }

        // Pre-rasterize common box-drawing characters (U+2500..U+257F)
        for ch in '\u{2500}'..='\u{257F}' {
            atlas.rasterize(GlyphKey {
                ch,
                bold: false,
                italic: false,
            });
        }

        // Block elements (U+2580..U+259F)
        for ch in '\u{2580}'..='\u{259F}' {
            atlas.rasterize(GlyphKey {
                ch,
                bold: false,
                italic: false,
            });
        }

        // Powerline symbols (U+E0A0..U+E0D4) -- branch, lock, line number, etc.
        for cp in 0xE0A0u32..=0xE0D4 {
            if let Some(ch) = char::from_u32(cp) {
                atlas.rasterize(GlyphKey {
                    ch,
                    bold: false,
                    italic: false,
                });
            }
        }

        // Powerline Extra symbols (U+E0B0..U+E0C8) -- arrow separators, flames, etc.
        // (overlaps with above range, rasterize is idempotent due to cache check)

        // Seti-UI / Custom (U+E5FA..U+E6AC) -- file type icons
        for cp in 0xE5FAu32..=0xE6AC {
            if let Some(ch) = char::from_u32(cp) {
                atlas.rasterize(GlyphKey {
                    ch,
                    bold: false,
                    italic: false,
                });
            }
        }

        // Devicons (U+E700..U+E7C5) -- programming language and tool icons
        for cp in 0xE700u32..=0xE7C5 {
            if let Some(ch) = char::from_u32(cp) {
                atlas.rasterize(GlyphKey {
                    ch,
                    bold: false,
                    italic: false,
                });
            }
        }

        // Font Awesome (U+F000..U+F2E0) -- general purpose icons
        for cp in 0xF000u32..=0xF2E0 {
            if let Some(ch) = char::from_u32(cp) {
                atlas.rasterize(GlyphKey {
                    ch,
                    bold: false,
                    italic: false,
                });
            }
        }

        // Font Awesome Extension (U+E200..U+E2A9) -- additional FA icons
        for cp in 0xE200u32..=0xE2A9 {
            if let Some(ch) = char::from_u32(cp) {
                atlas.rasterize(GlyphKey {
                    ch,
                    bold: false,
                    italic: false,
                });
            }
        }

        // Octicons (U+F400..U+F532, U+2665, U+26A1) -- GitHub icons
        for cp in 0xF400u32..=0xF532 {
            if let Some(ch) = char::from_u32(cp) {
                atlas.rasterize(GlyphKey {
                    ch,
                    bold: false,
                    italic: false,
                });
            }
        }

        // Material Design Icons (U+F0001..U+F1AF0) -- too many to pre-rasterize,
        // these will be rasterized on-demand via get_or_insert()

        log::info!(
            "GlyphAtlas: initialized {}x{}, cell={}x{}, baseline={}, entries={}",
            atlas_width,
            atlas_height,
            cell_width,
            cell_height,
            baseline,
            atlas.entries.len()
        );

        atlas
    }

    /// Look up a glyph in the atlas, rasterizing it on-demand if not cached.
    pub fn get_or_insert(&mut self, key: GlyphKey) -> AtlasEntry {
        if let Some(&entry) = self.entries.get(&key) {
            return entry;
        }
        self.rasterize(key)
    }

    /// Rasterize a glyph and insert it into the atlas.
    ///
    /// Font fallback order:
    /// 1. Bold font (if key.bold) or regular font
    /// 2. Regular font (fallback if bold lacks the glyph)
    /// 3. Empty entry (if no font covers the character -- e.g. color emoji)
    fn rasterize(&mut self, key: GlyphKey) -> AtlasEntry {
        // Already cached?
        if let Some(&entry) = self.entries.get(&key) {
            return entry;
        }

        // Select font with fallback: preferred -> other variant -> none
        let font = if key.bold {
            if self.font_bold.has_glyph(key.ch) {
                &self.font_bold
            } else if self.font_regular.has_glyph(key.ch) {
                &self.font_regular
            } else {
                // No font covers this glyph -- insert empty entry
                let entry = AtlasEntry {
                    x: 0,
                    y: 0,
                    width: 0,
                    height: 0,
                    offset_x: 0.0,
                    offset_y: 0.0,
                };
                self.entries.insert(key, entry);
                return entry;
            }
        } else if self.font_regular.has_glyph(key.ch) {
            &self.font_regular
        } else if self.font_bold.has_glyph(key.ch) {
            // Some glyphs might only exist in bold (unlikely but safe)
            &self.font_bold
        } else {
            // No font covers this glyph -- insert empty entry
            let entry = AtlasEntry {
                x: 0,
                y: 0,
                width: 0,
                height: 0,
                offset_x: 0.0,
                offset_y: 0.0,
            };
            self.entries.insert(key, entry);
            return entry;
        };

        let (metrics, bitmap) = font.rasterize(key.ch, self.font_size);

        let glyph_w = metrics.width as u32;
        let glyph_h = metrics.height as u32;

        // Handle zero-size glyphs (spaces, control chars)
        if glyph_w == 0 || glyph_h == 0 {
            let entry = AtlasEntry {
                x: 0,
                y: 0,
                width: 0,
                height: 0,
                offset_x: metrics.xmin as f32,
                offset_y: self.baseline - metrics.ymin as f32 - metrics.height as f32,
            };
            self.entries.insert(key, entry);
            return entry;
        }

        // Check if glyph fits in current row
        if self.cursor_x + glyph_w + self.padding > self.atlas_width {
            // Advance to next row
            self.cursor_y += self.row_height + self.padding;
            self.cursor_x = 1;
            self.row_height = 0;
        }

        // Check if we have room vertically
        if self.cursor_y + glyph_h + self.padding > self.atlas_height {
            // Atlas full -- grow it
            self.grow_atlas();
        }

        let x = self.cursor_x;
        let y = self.cursor_y;

        // Copy bitmap into atlas pixel buffer
        for row in 0..glyph_h {
            let src_start = (row * glyph_w) as usize;
            let src_end = src_start + glyph_w as usize;
            let dst_start = ((y + row) * self.atlas_width + x) as usize;
            self.pixels[dst_start..dst_start + glyph_w as usize]
                .copy_from_slice(&bitmap[src_start..src_end]);
        }

        // Offset: where the glyph bitmap should be placed relative to cell origin.
        // offset_x = metrics.xmin (horizontal bearing)
        // offset_y = baseline - (ymin + height) = distance from cell top to glyph top
        let offset_y = self.baseline - metrics.ymin as f32 - metrics.height as f32;

        let entry = AtlasEntry {
            x,
            y,
            width: glyph_w,
            height: glyph_h,
            offset_x: metrics.xmin as f32,
            offset_y,
        };

        self.entries.insert(key, entry);

        // Advance cursor
        self.cursor_x += glyph_w + self.padding;
        if glyph_h > self.row_height {
            self.row_height = glyph_h;
        }

        self.dirty = true;
        entry
    }

    /// Double the atlas height when full.
    fn grow_atlas(&mut self) {
        let new_height = self.atlas_height * 2;
        log::info!(
            "GlyphAtlas: growing from {}x{} to {}x{}",
            self.atlas_width,
            self.atlas_height,
            self.atlas_width,
            new_height
        );
        self.pixels
            .resize((self.atlas_width * new_height) as usize, 0u8);
        self.atlas_height = new_height;
        self.dirty = true;
    }

    /// Get the raw pixel data (R8 format) for uploading to the GPU texture.
    pub fn pixels(&self) -> &[u8] {
        &self.pixels
    }

    /// Number of cached glyphs.
    #[allow(dead_code)]
    pub fn entry_count(&self) -> usize {
        self.entries.len()
    }
}
