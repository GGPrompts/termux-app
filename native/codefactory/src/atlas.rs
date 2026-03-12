//! Glyph atlas: rasterizes glyphs on CPU using fontdue, packs them into a
//! dynamic texture atlas for GPU sampling.
//!
//! Architecture:
//! - `GlyphKey` = (char, bold, italic) -- each unique combo gets its own atlas entry
//! - Row-based packing: fill rows left-to-right, advance to next row when full
//! - HashMap<GlyphKey, AtlasEntry> for O(1) lookup
//! - Atlas starts at 1024x1024, grows by adding pages if needed
//! - Pre-rasterizes ASCII 0x20..=0x7E on init for cache warmth

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
    /// Loads the bundled JetBrains Mono Regular and Bold fonts.
    /// Pre-rasterizes ASCII printable range (0x20..=0x7E) for both regular and bold.
    pub fn new(font_size: f32) -> Self {
        let font_data_regular =
            include_bytes!("../assets/fonts/JetBrainsMono-Regular.ttf");
        let font_data_bold =
            include_bytes!("../assets/fonts/JetBrainsMono-Bold.ttf");

        let settings = fontdue::FontSettings {
            scale: font_size,
            ..fontdue::FontSettings::default()
        };

        let font_regular =
            fontdue::Font::from_bytes(font_data_regular.as_slice(), settings)
                .expect("Failed to load JetBrains Mono Regular");
        let font_bold =
            fontdue::Font::from_bytes(font_data_bold.as_slice(), settings)
                .expect("Failed to load JetBrains Mono Bold");

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

        let atlas_width = 1024u32;
        let atlas_height = 1024u32;
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
            entries: HashMap::with_capacity(256),
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

        // Pre-rasterize common box-drawing characters
        for ch in '\u{2500}'..='\u{257F}' {
            atlas.rasterize(GlyphKey {
                ch,
                bold: false,
                italic: false,
            });
        }

        // Block elements
        for ch in '\u{2580}'..='\u{259F}' {
            atlas.rasterize(GlyphKey {
                ch,
                bold: false,
                italic: false,
            });
        }

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
    fn rasterize(&mut self, key: GlyphKey) -> AtlasEntry {
        // Already cached?
        if let Some(&entry) = self.entries.get(&key) {
            return entry;
        }

        let font = if key.bold {
            &self.font_bold
        } else {
            &self.font_regular
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
