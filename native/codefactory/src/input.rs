//! Android key event -> terminal input conversion.
//!
//! Converts Android KeyEvent key codes and unicode characters into the byte
//! sequences that terminal applications expect. This handles:
//!
//! - Printable characters (unicode_char > 0) -> UTF-8 bytes
//! - Special keys (arrows, home, end, etc.) -> VT escape sequences
//! - Modifier combinations (Ctrl+C, Ctrl+Z, etc.) -> control characters
//! - Enter, Backspace, Tab, Escape -> standard terminal bytes
//!
//! The output bytes are written directly to the PTY -- no serialization layer.

/// Android KeyEvent key codes (subset relevant to terminal input).
/// Full list: https://developer.android.com/reference/android/view/KeyEvent
mod keycode {
    pub const KEYCODE_ENTER: i32 = 66;
    pub const KEYCODE_DEL: i32 = 67; // Backspace (Android calls it DEL)
    pub const KEYCODE_TAB: i32 = 61;
    pub const KEYCODE_ESCAPE: i32 = 111;

    // Arrow keys
    pub const KEYCODE_DPAD_UP: i32 = 19;
    pub const KEYCODE_DPAD_DOWN: i32 = 20;
    pub const KEYCODE_DPAD_LEFT: i32 = 21;
    pub const KEYCODE_DPAD_RIGHT: i32 = 22;

    // Navigation keys
    pub const KEYCODE_MOVE_HOME: i32 = 122;
    pub const KEYCODE_MOVE_END: i32 = 123;
    pub const KEYCODE_PAGE_UP: i32 = 92;
    pub const KEYCODE_PAGE_DOWN: i32 = 93;
    pub const KEYCODE_INSERT: i32 = 124;
    pub const KEYCODE_FORWARD_DEL: i32 = 112; // Delete (forward)

    // Function keys
    pub const KEYCODE_F1: i32 = 131;
    pub const KEYCODE_F2: i32 = 132;
    pub const KEYCODE_F3: i32 = 133;
    pub const KEYCODE_F4: i32 = 134;
    pub const KEYCODE_F5: i32 = 135;
    pub const KEYCODE_F6: i32 = 136;
    pub const KEYCODE_F7: i32 = 137;
    pub const KEYCODE_F8: i32 = 138;
    pub const KEYCODE_F9: i32 = 139;
    pub const KEYCODE_F10: i32 = 140;
    pub const KEYCODE_F11: i32 = 141;
    pub const KEYCODE_F12: i32 = 142;
}

/// Android KeyEvent meta state flags.
mod meta {
    pub const META_CTRL_ON: i32 = 0x1000;
    pub const META_ALT_ON: i32 = 0x02;
    pub const META_SHIFT_ON: i32 = 0x01;
}

/// Convert an Android key event to terminal input bytes.
///
/// Returns `Some(Vec<u8>)` with the bytes to write to the PTY, or `None`
/// if the key event should be ignored (e.g., modifier-only key press).
///
/// # Arguments
/// * `key_code` - Android KeyEvent.getKeyCode()
/// * `unicode_char` - Android KeyEvent.getUnicodeChar() (with meta state applied)
/// * `meta_state` - Android KeyEvent.getMetaState()
pub fn key_event_to_bytes(key_code: i32, unicode_char: i32, meta_state: i32) -> Option<Vec<u8>> {
    let ctrl = meta_state & meta::META_CTRL_ON != 0;
    let alt = meta_state & meta::META_ALT_ON != 0;
    let _shift = meta_state & meta::META_SHIFT_ON != 0;

    // Handle Ctrl+key combinations -> control characters
    if ctrl && unicode_char > 0 {
        let ch = unicode_char as u8;
        // Ctrl+A..Z -> 0x01..0x1A
        if (b'a'..=b'z').contains(&ch) || (b'A'..=b'Z').contains(&ch) {
            let ctrl_char = (ch & 0x1F) as u8;
            return Some(if alt {
                vec![0x1B, ctrl_char] // ESC + control char
            } else {
                vec![ctrl_char]
            });
        }
        // Ctrl+[ -> ESC (0x1B), Ctrl+\ -> 0x1C, Ctrl+] -> 0x1D
        // Ctrl+^ -> 0x1E, Ctrl+_ -> 0x1F
        match ch {
            b'[' => return Some(vec![0x1B]),
            b'\\' => return Some(vec![0x1C]),
            b']' => return Some(vec![0x1D]),
            b'^' | b'6' => return Some(vec![0x1E]),
            b'_' | b'-' => return Some(vec![0x1F]),
            b'@' | b'2' | b' ' => return Some(vec![0x00]), // Ctrl+Space / Ctrl+@ -> NUL
            _ => {}
        }
    }

    // Handle special keys by key code
    let special = match key_code {
        keycode::KEYCODE_ENTER => Some(b"\r".to_vec()),
        keycode::KEYCODE_DEL => Some(vec![0x7F]),              // Backspace -> DEL
        keycode::KEYCODE_TAB => Some(if _shift {
            b"\x1b[Z".to_vec()                                  // Shift+Tab -> backtab
        } else {
            b"\t".to_vec()
        }),
        keycode::KEYCODE_ESCAPE => Some(vec![0x1B]),

        // Arrow keys (CSI sequences)
        keycode::KEYCODE_DPAD_UP => Some(arrow_key(b'A', ctrl, alt)),
        keycode::KEYCODE_DPAD_DOWN => Some(arrow_key(b'B', ctrl, alt)),
        keycode::KEYCODE_DPAD_RIGHT => Some(arrow_key(b'C', ctrl, alt)),
        keycode::KEYCODE_DPAD_LEFT => Some(arrow_key(b'D', ctrl, alt)),

        // Navigation keys
        keycode::KEYCODE_MOVE_HOME => Some(nav_key(1, ctrl, alt)),
        keycode::KEYCODE_INSERT => Some(nav_key(2, ctrl, alt)),
        keycode::KEYCODE_FORWARD_DEL => Some(nav_key(3, ctrl, alt)),
        keycode::KEYCODE_MOVE_END => Some(nav_key(4, ctrl, alt)),
        keycode::KEYCODE_PAGE_UP => Some(nav_key(5, ctrl, alt)),
        keycode::KEYCODE_PAGE_DOWN => Some(nav_key(6, ctrl, alt)),

        // Function keys
        keycode::KEYCODE_F1 => Some(fkey(11)),
        keycode::KEYCODE_F2 => Some(fkey(12)),
        keycode::KEYCODE_F3 => Some(fkey(13)),
        keycode::KEYCODE_F4 => Some(fkey(14)),
        keycode::KEYCODE_F5 => Some(fkey(15)),
        keycode::KEYCODE_F6 => Some(fkey(17)),
        keycode::KEYCODE_F7 => Some(fkey(18)),
        keycode::KEYCODE_F8 => Some(fkey(19)),
        keycode::KEYCODE_F9 => Some(fkey(20)),
        keycode::KEYCODE_F10 => Some(fkey(21)),
        keycode::KEYCODE_F11 => Some(fkey(23)),
        keycode::KEYCODE_F12 => Some(fkey(24)),

        _ => None,
    };

    if special.is_some() {
        return special;
    }

    // Printable characters via unicode_char
    if unicode_char > 0 {
        if let Some(ch) = char::from_u32(unicode_char as u32) {
            let mut buf = [0u8; 4];
            let s = ch.encode_utf8(&mut buf);
            return Some(if alt {
                // Alt+key -> ESC prefix
                let mut v = vec![0x1B];
                v.extend_from_slice(s.as_bytes());
                v
            } else {
                s.as_bytes().to_vec()
            });
        }
    }

    // Unhandled key
    None
}

/// Generate an arrow key sequence with optional modifiers.
/// Normal: ESC[A  Ctrl: ESC[1;5A  Alt: ESC[1;3A
fn arrow_key(direction: u8, ctrl: bool, alt: bool) -> Vec<u8> {
    let modifier = modifier_param(ctrl, alt);
    if modifier > 1 {
        format!("\x1b[1;{}{}", modifier, direction as char)
            .into_bytes()
    } else {
        vec![0x1b, b'[', direction]
    }
}

/// Generate a navigation key sequence: ESC[N~ with optional modifiers.
/// Normal: ESC[1~  Ctrl: ESC[1;5~
fn nav_key(number: u8, ctrl: bool, alt: bool) -> Vec<u8> {
    let modifier = modifier_param(ctrl, alt);
    if modifier > 1 {
        format!("\x1b[{};{}~", number, modifier).into_bytes()
    } else {
        format!("\x1b[{}~", number).into_bytes()
    }
}

/// Generate a function key sequence: ESC[N~
fn fkey(number: u8) -> Vec<u8> {
    format!("\x1b[{}~", number).into_bytes()
}

/// Compute the xterm modifier parameter.
/// 2=Shift, 3=Alt, 4=Shift+Alt, 5=Ctrl, 6=Ctrl+Shift, 7=Ctrl+Alt
fn modifier_param(ctrl: bool, alt: bool) -> u8 {
    match (ctrl, alt) {
        (false, false) => 1,
        (false, true) => 3,
        (true, false) => 5,
        (true, true) => 7,
    }
}
