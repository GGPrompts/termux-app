//! Terminal cell grid: defines the interface between the terminal emulator
//! (alacritty_terminal) and the GPU renderer.
//!
//! This module provides:
//! - `CellStyle` -- per-cell styling (colors, attributes)
//! - `TermCell` -- a single cell in the grid (character + style)
//! - `TermGrid` -- the full terminal grid (rows x cols of TermCells)
//! - `CursorState` -- cursor position and style
//!
//! The actual alacritty_terminal integration (txc-6m6e) will convert
//! Grid<Cell> into TermGrid. For now, we provide a demo grid for testing.

use crate::colors::Rgba;

/// Text attributes for a terminal cell.
#[derive(Debug, Clone, Copy, Default)]
pub struct CellFlags {
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub strikethrough: bool,
    pub inverse: bool,
    pub dim: bool,
}

impl CellFlags {
    /// Pack flags into a u32 for the GPU instance buffer.
    pub fn to_gpu_flags(&self) -> u32 {
        let mut f = 0u32;
        if self.underline {
            f |= 1;
        }
        if self.strikethrough {
            f |= 2;
        }
        // Other flags are handled on the CPU side (inverse swaps colors,
        // bold/italic affect glyph lookup, dim reduces alpha)
        f
    }
}

/// Per-cell styling information.
#[derive(Debug, Clone, Copy)]
pub struct CellStyle {
    /// Foreground color (after resolving named/indexed/rgb)
    pub fg: Rgba,
    /// Background color (after resolving named/indexed/rgb)
    pub bg: Rgba,
    /// Text attribute flags
    pub flags: CellFlags,
}

/// A single cell in the terminal grid.
#[derive(Debug, Clone, Copy)]
pub struct TermCell {
    /// The character displayed in this cell (' ' for empty)
    pub ch: char,
    /// Cell styling
    pub style: CellStyle,
}

/// Cursor rendering style.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CursorStyle {
    Block,
    Underline,
    Bar,
}

/// Cursor state for rendering.
#[derive(Debug, Clone, Copy)]
pub struct CursorState {
    /// Column (0-indexed)
    pub col: usize,
    /// Row (0-indexed from top of visible area)
    pub row: usize,
    /// Cursor style
    pub style: CursorStyle,
    /// Cursor color
    pub color: Rgba,
    /// Whether cursor is visible (blink state)
    pub visible: bool,
}

/// Selection range for highlighting.
#[derive(Debug, Clone, Copy)]
pub struct Selection {
    /// Start column
    pub start_col: usize,
    /// Start row
    pub start_row: usize,
    /// End column
    pub end_col: usize,
    /// End row
    pub end_row: usize,
    /// Selection background color
    pub color: Rgba,
}

/// The terminal grid: a 2D array of styled cells.
#[derive(Clone)]
pub struct TermGrid {
    /// Number of columns
    pub cols: usize,
    /// Number of rows
    pub rows: usize,
    /// Cells stored in row-major order: cells[row * cols + col]
    pub cells: Vec<TermCell>,
    /// Cursor state
    pub cursor: CursorState,
    /// Active selection, if any
    pub selection: Option<Selection>,
}

impl TermGrid {
    /// Create an empty grid filled with spaces.
    pub fn new(cols: usize, rows: usize, default_fg: Rgba, default_bg: Rgba) -> Self {
        let default_cell = TermCell {
            ch: ' ',
            style: CellStyle {
                fg: default_fg,
                bg: default_bg,
                flags: CellFlags::default(),
            },
        };

        Self {
            cols,
            rows,
            cells: vec![default_cell; cols * rows],
            cursor: CursorState {
                col: 0,
                row: 0,
                style: CursorStyle::Block,
                color: Rgba::from_rgb8(0xF5, 0xE0, 0xDC),
                visible: true,
            },
            selection: None,
        }
    }

    /// Get a cell reference at (col, row).
    #[inline]
    pub fn cell(&self, col: usize, row: usize) -> &TermCell {
        &self.cells[row * self.cols + col]
    }

    /// Get a mutable cell reference at (col, row).
    #[inline]
    pub fn cell_mut(&mut self, col: usize, row: usize) -> &mut TermCell {
        &mut self.cells[row * self.cols + col]
    }

    /// Write a string at position (col, row) with the given style.
    pub fn write_str(&mut self, col: usize, row: usize, text: &str, style: CellStyle) {
        for (i, ch) in text.chars().enumerate() {
            let c = col + i;
            if c >= self.cols {
                break;
            }
            let cell = self.cell_mut(c, row);
            cell.ch = ch;
            cell.style = style;
        }
    }

    /// Create a demo grid with various terminal content for visual testing.
    pub fn demo(cols: usize, rows: usize) -> Self {
        use crate::colors::ColorTheme;

        let theme = ColorTheme::default();
        let mut grid = Self::new(cols, rows, theme.fg, theme.bg);

        let default_style = CellStyle {
            fg: theme.fg,
            bg: theme.bg,
            flags: CellFlags::default(),
        };

        // Title line
        let title_style = CellStyle {
            fg: Rgba::from_rgb8(0x89, 0xB4, 0xFA), // blue
            bg: theme.bg,
            flags: CellFlags {
                bold: true,
                ..Default::default()
            },
        };
        grid.write_str(0, 0, "CodeFactory GPU Terminal", title_style);

        // Separator
        let sep: String = "\u{2500}".repeat(cols.min(80));
        grid.write_str(0, 1, &sep, default_style);

        // ANSI colors demo
        let label_style = CellStyle {
            fg: theme.fg,
            bg: theme.bg,
            flags: CellFlags {
                dim: true,
                ..Default::default()
            },
        };
        grid.write_str(0, 3, "ANSI Colors:", label_style);

        // Show the 16 ANSI colors as colored blocks
        let color_names = [
            "Blk", "Red", "Grn", "Yel", "Blu", "Mag", "Cyn", "Wht",
        ];
        for (i, name) in color_names.iter().enumerate() {
            let style = CellStyle {
                fg: theme.bg, // dark text on colored bg
                bg: theme.ansi[i],
                flags: CellFlags::default(),
            };
            let col_pos = i * 5;
            grid.write_str(col_pos, 4, &format!(" {} ", name), style);
        }
        for (i, name) in color_names.iter().enumerate() {
            let style = CellStyle {
                fg: theme.bg,
                bg: theme.ansi[i + 8],
                flags: CellFlags::default(),
            };
            let col_pos = i * 5;
            grid.write_str(col_pos, 5, &format!(" {} ", name), style);
        }

        // 256-color ramp
        if rows > 8 {
            grid.write_str(0, 7, "256-color palette:", label_style);
            for i in 0..72u8 {
                let ci = (i + 16) as u8; // Start from color cube
                let color = theme.resolve_indexed(ci);
                let style = CellStyle {
                    fg: color,
                    bg: color,
                    flags: CellFlags::default(),
                };
                let col_pos = i as usize;
                if col_pos < cols {
                    grid.write_str(col_pos, 8, "\u{2588}", style);
                }
            }
        }

        // True color gradient
        if rows > 10 {
            grid.write_str(0, 10, "True color gradient:", label_style);
            for i in 0..cols.min(72) {
                let t = i as f32 / 72.0;
                let r = (t * 255.0) as u8;
                let g = ((1.0 - t) * 200.0) as u8;
                let b = ((t * 2.0 * std::f32::consts::PI).sin().abs() * 255.0) as u8;
                let style = CellStyle {
                    fg: Rgba::from_rgb8(r, g, b),
                    bg: Rgba::from_rgb8(r, g, b),
                    flags: CellFlags::default(),
                };
                grid.write_str(i, 11, "\u{2588}", style);
            }
        }

        // Text attributes
        if rows > 13 {
            grid.write_str(0, 13, "Text attributes:", label_style);

            let bold_style = CellStyle {
                fg: theme.fg,
                bg: theme.bg,
                flags: CellFlags {
                    bold: true,
                    ..Default::default()
                },
            };
            grid.write_str(0, 14, "Bold text", bold_style);

            let italic_style = CellStyle {
                fg: theme.fg,
                bg: theme.bg,
                flags: CellFlags {
                    italic: true,
                    ..Default::default()
                },
            };
            grid.write_str(12, 14, "Italic text", italic_style);

            let underline_style = CellStyle {
                fg: theme.fg,
                bg: theme.bg,
                flags: CellFlags {
                    underline: true,
                    ..Default::default()
                },
            };
            grid.write_str(25, 14, "Underlined", underline_style);

            let strike_style = CellStyle {
                fg: theme.fg,
                bg: theme.bg,
                flags: CellFlags {
                    strikethrough: true,
                    ..Default::default()
                },
            };
            grid.write_str(37, 14, "Strikethrough", strike_style);
        }

        // Box drawing
        if rows > 16 {
            grid.write_str(0, 16, "Box drawing:", label_style);
            let box_style = CellStyle {
                fg: Rgba::from_rgb8(0x94, 0xE2, 0xD5), // teal
                bg: theme.bg,
                flags: CellFlags::default(),
            };
            grid.write_str(0, 17, "\u{250C}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2510}", box_style);
            grid.write_str(0, 18, "\u{2502}  CodeFactory  \u{2502}", box_style);
            grid.write_str(0, 19, "\u{2514}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2518}", box_style);
        }

        // Simulated prompt
        if rows > 21 {
            let prompt_style = CellStyle {
                fg: Rgba::from_rgb8(0xA6, 0xE3, 0xA1), // green
                bg: theme.bg,
                flags: CellFlags {
                    bold: true,
                    ..Default::default()
                },
            };
            let path_style = CellStyle {
                fg: Rgba::from_rgb8(0x89, 0xB4, 0xFA), // blue
                bg: theme.bg,
                flags: CellFlags {
                    bold: true,
                    ..Default::default()
                },
            };
            grid.write_str(0, 21, "user@codefactory", prompt_style);
            grid.write_str(16, 21, ":", default_style);
            grid.write_str(17, 21, "~/projects", path_style);
            grid.write_str(27, 21, "$ ", default_style);

            // Cursor on the command line
            grid.cursor.col = 29;
            grid.cursor.row = 21;
        }

        grid
    }
}
