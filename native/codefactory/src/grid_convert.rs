//! Converts alacritty_terminal's Grid<Cell> into the renderer's TermGrid.
//!
//! This is the bridge between the terminal emulator's internal representation
//! (alacritty_terminal types) and the GPU renderer's data structures. The
//! conversion reads the visible screen lines from the alacritty grid, extracts
//! character + styling information per cell, and maps terminal colors to RGBA
//! values using the active ColorTheme.
//!
//! Performance note: this runs once per frame (when dirty). A typical 80x24
//! terminal is 1920 cells -- trivial even on mobile. The hot path avoids
//! allocations by reusing the TermGrid's cell buffer.

use alacritty_terminal::grid::Dimensions;
use alacritty_terminal::index::{Column, Line};
use alacritty_terminal::term::cell::{Cell, Flags};
use alacritty_terminal::term::Term;
use alacritty_terminal::vte::ansi::{Color, CursorShape, NamedColor};

use crate::colors::{ColorTheme, Rgba};
use crate::grid::{CellFlags, CellStyle, CursorState, CursorStyle, TermCell, TermGrid};

/// Extract the cursor position from a Term and convert to grid coordinates.
fn extract_cursor<L: alacritty_terminal::event::EventListener>(
    term: &Term<L>,
) -> CursorState {
    let cursor = &term.grid().cursor;
    let point = &cursor.point;
    let cursor_style = term.cursor_style();

    let (style, visible) = match cursor_style.shape {
        CursorShape::Block => (CursorStyle::Block, true),
        CursorShape::Underline => (CursorStyle::Underline, true),
        CursorShape::Beam => (CursorStyle::Bar, true),
        CursorShape::HollowBlock => (CursorStyle::Block, true),
        CursorShape::Hidden => (CursorStyle::Block, false),
    };

    CursorState {
        col: point.column.0,
        row: point.line.0 as usize,
        style,
        color: Rgba::from_rgb8(0xF5, 0xE0, 0xDC), // rosewater
        visible,
    }
}

/// Resolve an alacritty_terminal Color to an Rgba using the color theme.
///
/// Handles all three color types:
/// - Named: ANSI 0-15, plus Dim variants, plus special Foreground/Background/Cursor
/// - Spec: 24-bit true color RGB
/// - Indexed: 256-color palette
fn resolve_color(color: &Color, theme: &ColorTheme, default: Rgba) -> Rgba {
    match color {
        Color::Named(named) => resolve_named_color(*named, theme, default),
        Color::Spec(rgb) => Rgba::from_rgb8(rgb.r, rgb.g, rgb.b),
        Color::Indexed(idx) => theme.resolve_indexed(*idx),
    }
}

/// Map a NamedColor to Rgba. Covers ANSI 0-15, Dim 0-7, and special names.
fn resolve_named_color(named: NamedColor, theme: &ColorTheme, default: Rgba) -> Rgba {
    match named {
        // Standard ANSI 0-7
        NamedColor::Black => theme.ansi[0],
        NamedColor::Red => theme.ansi[1],
        NamedColor::Green => theme.ansi[2],
        NamedColor::Yellow => theme.ansi[3],
        NamedColor::Blue => theme.ansi[4],
        NamedColor::Magenta => theme.ansi[5],
        NamedColor::Cyan => theme.ansi[6],
        NamedColor::White => theme.ansi[7],
        // Bright ANSI 8-15
        NamedColor::BrightBlack => theme.ansi[8],
        NamedColor::BrightRed => theme.ansi[9],
        NamedColor::BrightGreen => theme.ansi[10],
        NamedColor::BrightYellow => theme.ansi[11],
        NamedColor::BrightBlue => theme.ansi[12],
        NamedColor::BrightMagenta => theme.ansi[13],
        NamedColor::BrightCyan => theme.ansi[14],
        NamedColor::BrightWhite => theme.ansi[15],
        // Dim variants -- same base color with reduced intensity
        NamedColor::DimBlack => dim_color(theme.ansi[0]),
        NamedColor::DimRed => dim_color(theme.ansi[1]),
        NamedColor::DimGreen => dim_color(theme.ansi[2]),
        NamedColor::DimYellow => dim_color(theme.ansi[3]),
        NamedColor::DimBlue => dim_color(theme.ansi[4]),
        NamedColor::DimMagenta => dim_color(theme.ansi[5]),
        NamedColor::DimCyan => dim_color(theme.ansi[6]),
        NamedColor::DimWhite => dim_color(theme.ansi[7]),
        // Special named colors
        NamedColor::Foreground | NamedColor::BrightForeground => theme.fg,
        NamedColor::Background => theme.bg,
        NamedColor::Cursor => theme.cursor,
        NamedColor::DimForeground => dim_color(theme.fg),
    }
}

/// Reduce color intensity by 33% (dim attribute).
#[inline]
fn dim_color(c: Rgba) -> Rgba {
    Rgba::new(c.r * 0.67, c.g * 0.67, c.b * 0.67, c.a)
}

/// Convert alacritty_terminal Cell flags to our CellFlags.
fn convert_flags(flags: Flags) -> CellFlags {
    CellFlags {
        bold: flags.contains(Flags::BOLD),
        italic: flags.contains(Flags::ITALIC),
        underline: flags.contains(Flags::UNDERLINE) || flags.contains(Flags::DOUBLE_UNDERLINE),
        strikethrough: flags.contains(Flags::STRIKEOUT),
        inverse: flags.contains(Flags::INVERSE),
        dim: flags.contains(Flags::DIM_BOLD) || flags.contains(Flags::DIM),
    }
}

/// Convert a single alacritty_terminal Cell to our TermCell.
fn convert_cell(cell: &Cell, theme: &ColorTheme) -> TermCell {
    let fg = resolve_color(&cell.fg, theme, theme.fg);
    let bg = resolve_color(&cell.bg, theme, theme.bg);
    let flags = convert_flags(cell.flags);

    TermCell {
        ch: cell.c,
        style: CellStyle { fg, bg, flags },
    }
}

/// Convert an alacritty_terminal Grid<Cell> to a TermGrid.
///
/// This reads only the visible screen lines (not scrollback) and maps each
/// cell's character and styling to the renderer's format. The cursor position
/// is extracted from the Term.
///
/// This function allocates a new TermGrid each time. For dirty-tracking
/// optimization, use `update_grid` instead which reuses an existing buffer.
pub fn grid_from_term<L: alacritty_terminal::event::EventListener>(
    term: &Term<L>,
    theme: &ColorTheme,
) -> TermGrid {
    let grid = term.grid();
    let cols = grid.columns();
    let rows = grid.screen_lines();

    let mut term_grid = TermGrid::new(cols, rows, theme.fg, theme.bg);

    for row in 0..rows {
        let line = Line(row as i32);
        for col in 0..cols {
            let cell = &grid[line][Column(col)];
            let term_cell = convert_cell(cell, theme);
            *term_grid.cell_mut(col, row) = term_cell;
        }
    }

    term_grid.cursor = extract_cursor(term);
    term_grid
}

/// Update an existing TermGrid in-place from a Term, returning which rows changed.
///
/// Compares each cell against the existing grid content and only updates cells
/// that differ. Returns a bitmask (Vec<bool>) indicating which rows were modified.
/// The caller can use this to decide whether to re-upload instance data.
///
/// If the grid dimensions changed (resize), returns None to signal a full rebuild.
pub fn update_grid<L: alacritty_terminal::event::EventListener>(
    term_grid: &mut TermGrid,
    term: &Term<L>,
    theme: &ColorTheme,
) -> Option<Vec<bool>> {
    let grid = term.grid();
    let cols = grid.columns();
    let rows = grid.screen_lines();

    // If dimensions changed, caller must do a full rebuild
    if cols != term_grid.cols || rows != term_grid.rows {
        return None;
    }

    let mut dirty_rows = vec![false; rows];

    for row in 0..rows {
        let line = Line(row as i32);
        for col in 0..cols {
            let cell = &grid[line][Column(col)];
            let new_cell = convert_cell(cell, theme);
            let existing = term_grid.cell(col, row);

            // Compare character and key style attributes
            if existing.ch != new_cell.ch
                || !colors_equal(&existing.style.fg, &new_cell.style.fg)
                || !colors_equal(&existing.style.bg, &new_cell.style.bg)
                || existing.style.flags.bold != new_cell.style.flags.bold
                || existing.style.flags.italic != new_cell.style.flags.italic
                || existing.style.flags.underline != new_cell.style.flags.underline
                || existing.style.flags.strikethrough != new_cell.style.flags.strikethrough
                || existing.style.flags.inverse != new_cell.style.flags.inverse
                || existing.style.flags.dim != new_cell.style.flags.dim
            {
                *term_grid.cell_mut(col, row) = new_cell;
                dirty_rows[row] = true;
            }
        }
    }

    // Update cursor
    let new_cursor = extract_cursor(term);
    if term_grid.cursor.col != new_cursor.col
        || term_grid.cursor.row != new_cursor.row
        || term_grid.cursor.style != new_cursor.style
    {
        // Mark both old and new cursor rows as dirty
        if term_grid.cursor.row < rows {
            dirty_rows[term_grid.cursor.row] = true;
        }
        if new_cursor.row < rows {
            dirty_rows[new_cursor.row] = true;
        }
        term_grid.cursor = new_cursor;
    }

    Some(dirty_rows)
}

/// Fast RGBA comparison (avoids floating-point fuzziness by comparing as bits).
#[inline]
fn colors_equal(a: &Rgba, b: &Rgba) -> bool {
    a.r.to_bits() == b.r.to_bits()
        && a.g.to_bits() == b.g.to_bits()
        && a.b.to_bits() == b.b.to_bits()
        && a.a.to_bits() == b.a.to_bits()
}
