//! Terminal color handling: ANSI 16, 256-color xterm palette, true color (24-bit RGB).
//!
//! Provides a `ColorTheme` with configurable ANSI colors and conversion from
//! terminal color values to RGBA floats for the GPU.

/// RGBA color as 4 f32s (0.0..1.0), ready for GPU uniform/instance data.
#[derive(Debug, Clone, Copy)]
pub struct Rgba {
    pub r: f32,
    pub g: f32,
    pub b: f32,
    pub a: f32,
}

impl Rgba {
    pub const fn new(r: f32, g: f32, b: f32, a: f32) -> Self {
        Self { r, g, b, a }
    }

    /// Create from 8-bit RGB values.
    pub fn from_rgb8(r: u8, g: u8, b: u8) -> Self {
        Self {
            r: r as f32 / 255.0,
            g: g as f32 / 255.0,
            b: b as f32 / 255.0,
            a: 1.0,
        }
    }

    pub fn to_array(self) -> [f32; 4] {
        [self.r, self.g, self.b, self.a]
    }
}

/// Terminal color theme with configurable ANSI 16 colors.
/// Based on Catppuccin Mocha as default.
pub struct ColorTheme {
    /// The 16 ANSI colors (0-7 normal, 8-15 bright)
    pub ansi: [Rgba; 16],
    /// Default foreground
    pub fg: Rgba,
    /// Default background
    pub bg: Rgba,
    /// Cursor color
    pub cursor: Rgba,
}

impl Default for ColorTheme {
    fn default() -> Self {
        Self::catppuccin_mocha()
    }
}

impl ColorTheme {
    /// Catppuccin Mocha color scheme -- popular dark terminal theme.
    pub fn catppuccin_mocha() -> Self {
        Self {
            ansi: [
                // Normal colors (0-7)
                Rgba::from_rgb8(0x45, 0x47, 0x5A), // 0: black (surface1)
                Rgba::from_rgb8(0xF3, 0x8B, 0xA8), // 1: red
                Rgba::from_rgb8(0xA6, 0xE3, 0xA1), // 2: green
                Rgba::from_rgb8(0xF9, 0xE2, 0xAF), // 3: yellow
                Rgba::from_rgb8(0x89, 0xB4, 0xFA), // 4: blue
                Rgba::from_rgb8(0xF5, 0xC2, 0xE7), // 5: magenta (pink)
                Rgba::from_rgb8(0x94, 0xE2, 0xD5), // 6: cyan (teal)
                Rgba::from_rgb8(0xBA, 0xC2, 0xDE), // 7: white (subtext1)
                // Bright colors (8-15)
                Rgba::from_rgb8(0x58, 0x5B, 0x70), // 8: bright black (surface2)
                Rgba::from_rgb8(0xF3, 0x8B, 0xA8), // 9: bright red
                Rgba::from_rgb8(0xA6, 0xE3, 0xA1), // 10: bright green
                Rgba::from_rgb8(0xF9, 0xE2, 0xAF), // 11: bright yellow
                Rgba::from_rgb8(0x89, 0xB4, 0xFA), // 12: bright blue
                Rgba::from_rgb8(0xF5, 0xC2, 0xE7), // 13: bright magenta
                Rgba::from_rgb8(0x94, 0xE2, 0xD5), // 14: bright cyan
                Rgba::from_rgb8(0xA6, 0xAD, 0xC8), // 15: bright white (subtext0)
            ],
            fg: Rgba::from_rgb8(0xCD, 0xD6, 0xF4),     // text
            bg: Rgba::from_rgb8(0x1E, 0x1E, 0x2E),     // base
            cursor: Rgba::from_rgb8(0xF5, 0xE0, 0xDC),  // rosewater
        }
    }

    /// Resolve a 256-color indexed color.
    pub fn resolve_indexed(&self, index: u8) -> Rgba {
        let i = index as usize;
        if i < 16 {
            // ANSI colors
            self.ansi[i]
        } else if i < 232 {
            // 216-color cube: indices 16-231
            // Each component: 0, 95, 135, 175, 215, 255  (6 levels)
            let idx = i - 16;
            let r_idx = idx / 36;
            let g_idx = (idx % 36) / 6;
            let b_idx = idx % 6;
            let r = if r_idx == 0 { 0 } else { 55 + r_idx as u8 * 40 };
            let g = if g_idx == 0 { 0 } else { 55 + g_idx as u8 * 40 };
            let b = if b_idx == 0 { 0 } else { 55 + b_idx as u8 * 40 };
            Rgba::from_rgb8(r, g, b)
        } else {
            // Grayscale ramp: indices 232-255
            // 24 shades from 8 to 238 in steps of 10
            let shade = 8 + (i - 232) as u8 * 10;
            Rgba::from_rgb8(shade, shade, shade)
        }
    }

}
