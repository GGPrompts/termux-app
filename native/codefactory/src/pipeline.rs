//! Terminal pipeline: PTY -> parser -> renderer.
//!
//! This module wires the complete in-process terminal path:
//!
//! 1. **PTY reader thread**: reads bytes from portable-pty, feeds them to
//!    `alacritty_terminal::Term::process_bytes()`, sets a dirty flag.
//!
//! 2. **Render path**: on each frame request (from JNI `nativeRenderFrame`),
//!    checks the dirty flag, converts the alacritty grid to TermGrid, and
//!    hands it to the wgpu renderer.
//!
//! 3. **Input path**: key events from JNI are converted to terminal bytes
//!    (via `input.rs`) and written to the PTY. Echo appears via the normal
//!    PTY -> parser -> renderer path.
//!
//! 4. **WebSocket fallback**: the raw PTY bytes are optionally forwarded to
//!    a WebSocket subscriber for remote access (web dashboard).
//!
//! Synchronization uses `Arc<Mutex<TermState>>` -- simple and correct.
//! The mutex is held briefly: PTY reader writes ~4KB chunks, renderer reads
//! the grid once per frame. At 60fps with typical terminal output, contention
//! is negligible.

use std::io::{Read, Write};
use std::os::unix::io::{FromRawFd, RawFd};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use alacritty_terminal::event::{Event, EventListener};
use alacritty_terminal::grid::Dimensions;
use alacritty_terminal::term::Config;
use alacritty_terminal::vte::ansi;
use alacritty_terminal::Term;
use portable_pty::{native_pty_system, CommandBuilder, PtySize};

use crate::colors::ColorTheme;
use crate::grid::TermGrid;
use crate::grid_convert;
use crate::input;

// ---------------------------------------------------------------------------
// EventProxy -- receives events from alacritty_terminal::Term
// ---------------------------------------------------------------------------

/// Receives events from the terminal emulator. PtyWrite events are forwarded
/// back to the PTY (e.g., device attribute responses). Other events are logged.
#[derive(Clone)]
struct PipelineEventProxy {
    /// Channel to send PtyWrite data back to the PTY writer thread.
    pty_write_tx: std::sync::mpsc::Sender<Vec<u8>>,
}

impl PipelineEventProxy {
    fn new(pty_write_tx: std::sync::mpsc::Sender<Vec<u8>>) -> Self {
        Self { pty_write_tx }
    }
}

impl EventListener for PipelineEventProxy {
    fn send_event(&self, event: Event) {
        match event {
            Event::PtyWrite(text) => {
                // Terminal wants to write back to PTY (e.g., DA response).
                // Forward through the input channel.
                let _ = self.pty_write_tx.send(text.into_bytes());
            }
            Event::Title(ref title) => {
                log::debug!("Terminal title: {}", title);
            }
            Event::Bell => {
                log::trace!("Terminal bell");
            }
            Event::Exit | Event::ChildExit(_) => {
                log::info!("Terminal exit: {:?}", event);
            }
            // High-frequency events -- suppress
            Event::Wakeup | Event::MouseCursorDirty | Event::CursorBlinkingChange => {}
            _ => {
                log::trace!("Terminal event: {:?}", event);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// TermSize -- dimensions for Term::new / Term::resize
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy)]
struct TermSize {
    columns: usize,
    screen_lines: usize,
}

impl Dimensions for TermSize {
    fn total_lines(&self) -> usize {
        self.screen_lines
    }
    fn screen_lines(&self) -> usize {
        self.screen_lines
    }
    fn columns(&self) -> usize {
        self.columns
    }
}

// ---------------------------------------------------------------------------
// Shared terminal state (protected by mutex)
// ---------------------------------------------------------------------------

/// The shared state between the PTY reader thread and the render thread.
struct TerminalState {
    term: Term<PipelineEventProxy>,
    parser: ansi::Processor,
}

impl TerminalState {
    fn new(cols: u16, rows: u16, pty_write_tx: std::sync::mpsc::Sender<Vec<u8>>) -> Self {
        let config = Config {
            scrolling_history: 10_000,
            ..Config::default()
        };
        let size = TermSize {
            columns: cols as usize,
            screen_lines: rows as usize,
        };
        let event_proxy = PipelineEventProxy::new(pty_write_tx);
        let term = Term::new(config, &size, event_proxy);
        let parser = ansi::Processor::new();
        Self { term, parser }
    }

    fn process_bytes(&mut self, bytes: &[u8]) {
        self.parser.advance(&mut self.term, bytes);
    }

    fn resize(&mut self, cols: u16, rows: u16) {
        let size = TermSize {
            columns: cols as usize,
            screen_lines: rows as usize,
        };
        self.term.resize(size);
    }
}

// ---------------------------------------------------------------------------
// TerminalPipeline -- the public interface
// ---------------------------------------------------------------------------

/// The complete terminal pipeline: PTY + parser + grid bridge.
///
/// Created by `TerminalPipeline::spawn()` (owns PTY) or
/// `TerminalPipeline::attach_fd()` (borrows fd from Java TerminalSession).
/// The pipeline runs until the PTY exits or `shutdown()` is called.
pub struct TerminalPipeline {
    /// Shared terminal state (PTY reader writes, renderer reads).
    state: Arc<Mutex<TerminalState>>,

    /// Channel to send input bytes to the PTY writer thread.
    /// This includes both user keyboard input and terminal-originated
    /// PtyWrite events (DA responses, etc.).
    input_tx: std::sync::mpsc::Sender<Vec<u8>>,

    /// Monotonically increasing counter bumped on every process_bytes call.
    /// The renderer compares this against its last-seen value to know if
    /// the grid needs re-conversion.
    write_gen: Arc<AtomicU64>,

    /// Last generation the renderer consumed. If write_gen > render_gen,
    /// the grid is dirty and needs re-conversion.
    render_gen: AtomicU64,

    /// Set to false when the PTY reader thread exits.
    alive: Arc<AtomicBool>,

    /// PTY master -- kept alive for resize operations.
    /// Dropping this closes the PTY, which causes the reader thread to EOF.
    /// None when attached to an external fd (Java owns the PTY).
    pty_master: Option<Box<dyn portable_pty::MasterPty + Send>>,

    /// Raw fd of the PTY master. Used for resize when attached to an
    /// external fd (Java-owned PTY). -1 when using portable-pty (which
    /// handles resize through its own API).
    attached_fd: RawFd,

    /// Current grid dimensions (cols, rows).
    cols: u16,
    rows: u16,

    /// Optional WebSocket output sink for remote access.
    /// When set, raw PTY bytes are cloned and sent through this channel
    /// in parallel with the native rendering path.
    ws_sink: Arc<Mutex<Option<std::sync::mpsc::Sender<Vec<u8>>>>>,

    /// Color theme for grid conversion.
    theme: ColorTheme,

    /// Cached TermGrid for incremental updates.
    cached_grid: Option<TermGrid>,
}

impl TerminalPipeline {
    /// Spawn a new terminal pipeline.
    ///
    /// Creates a PTY, starts a shell (or the given command), and begins
    /// reading PTY output in a background thread. The terminal emulator
    /// state is updated in-place as bytes arrive.
    ///
    /// # Arguments
    /// * `cols` - Terminal width in columns
    /// * `rows` - Terminal height in rows
    /// * `shell` - Shell command to run (None = default shell)
    pub fn spawn(cols: u16, rows: u16, shell: Option<&str>) -> Result<Self, String> {
        log::info!("TerminalPipeline::spawn: {}x{}", cols, rows);

        // Open PTY
        let pty_system = native_pty_system();
        let pair = pty_system
            .openpty(PtySize {
                rows,
                cols,
                pixel_width: 0,
                pixel_height: 0,
            })
            .map_err(|e| format!("Failed to open PTY: {}", e))?;

        // Detect Termux environment
        let termux_prefix = "/data/data/com.termux/files/usr";
        let termux_home = "/data/data/com.termux/files/home";
        let is_termux = std::path::Path::new(termux_prefix).exists();

        // Build command -- detect shell with Termux-aware fallbacks
        let shell_cmd = match shell {
            Some(s) => s.to_string(),
            None => {
                if is_termux {
                    // Try common shells in Termux prefix
                    let candidates = [
                        format!("{}/bin/bash", termux_prefix),
                        format!("{}/bin/zsh", termux_prefix),
                        format!("{}/bin/sh", termux_prefix),
                    ];
                    candidates
                        .iter()
                        .find(|p| std::path::Path::new(p).exists())
                        .cloned()
                        .unwrap_or_else(|| {
                            std::env::var("SHELL").unwrap_or_else(|_| "/bin/sh".to_string())
                        })
                } else {
                    std::env::var("SHELL").unwrap_or_else(|_| "/bin/sh".to_string())
                }
            }
        };
        log::info!("TerminalPipeline::spawn: shell={}, is_termux={}", shell_cmd, is_termux);

        let mut cmd = CommandBuilder::new(&shell_cmd);
        // Login shell flag for proper profile/rc sourcing
        cmd.arg("-l");

        cmd.env("TERM", "xterm-256color");
        cmd.env("COLORTERM", "truecolor");
        cmd.env("LANG", "en_US.UTF-8");

        if is_termux {
            // Set up full Termux environment so the shell behaves normally
            cmd.env("PREFIX", termux_prefix);
            cmd.env("HOME", termux_home);
            cmd.env(
                "PATH",
                format!("{0}/bin:{0}/bin/applets", termux_prefix),
            );
            cmd.env("TMPDIR", format!("{}/tmp", termux_prefix));
            cmd.env("LD_LIBRARY_PATH", format!("{}/lib", termux_prefix));
            cmd.env("SHELL", &shell_cmd);
            // Android-specific
            cmd.env("ANDROID_DATA", "/data");
            cmd.env("ANDROID_ROOT", "/system");
            cmd.cwd(termux_home);
        } else {
            // Non-Termux: use system defaults
            if let Ok(home) = std::env::var("HOME") {
                cmd.env("HOME", &home);
                cmd.cwd(&home);
            }
        }

        // Spawn the child process in the PTY
        let _child = pair
            .slave
            .spawn_command(cmd)
            .map_err(|e| format!("Failed to spawn shell: {}", e))?;

        let reader = pair
            .master
            .try_clone_reader()
            .map_err(|e| format!("Failed to clone PTY reader: {}", e))?;
        let writer = pair
            .master
            .take_writer()
            .map_err(|e| format!("Failed to take PTY writer: {}", e))?;

        // Create the input channel (user input + PtyWrite events)
        let (input_tx, input_rx) = std::sync::mpsc::channel::<Vec<u8>>();

        // Create terminal state
        let state = Arc::new(Mutex::new(TerminalState::new(cols, rows, input_tx.clone())));

        let write_gen = Arc::new(AtomicU64::new(0));
        let alive = Arc::new(AtomicBool::new(true));
        let ws_sink: Arc<Mutex<Option<std::sync::mpsc::Sender<Vec<u8>>>>> =
            Arc::new(Mutex::new(None));

        // --- PTY reader thread ---
        // Reads bytes from PTY, feeds them through the terminal parser.
        let reader_state = Arc::clone(&state);
        let reader_gen = Arc::clone(&write_gen);
        let reader_alive = Arc::clone(&alive);
        let reader_ws_sink = Arc::clone(&ws_sink);

        std::thread::Builder::new()
            .name("pty-reader".into())
            .spawn(move || {
                let mut reader = reader;
                let mut buf = [0u8; 8192]; // Larger buffer for throughput
                loop {
                    match reader.read(&mut buf) {
                        Ok(0) => {
                            log::info!("PTY reader: EOF");
                            reader_alive.store(false, Ordering::SeqCst);
                            break;
                        }
                        Ok(n) => {
                            let bytes = &buf[..n];

                            // Feed bytes to terminal parser (under lock)
                            if let Ok(mut ts) = reader_state.lock() {
                                ts.process_bytes(bytes);
                            }
                            // Bump generation counter (atomic, no lock)
                            reader_gen.fetch_add(1, Ordering::Release);

                            // Forward to WebSocket sink if connected
                            if let Ok(guard) = reader_ws_sink.lock() {
                                if let Some(ref tx) = *guard {
                                    let _ = tx.send(bytes.to_vec());
                                }
                            }
                        }
                        Err(e) => {
                            log::error!("PTY reader error: {}", e);
                            reader_alive.store(false, Ordering::SeqCst);
                            break;
                        }
                    }
                }
            })
            .map_err(|e| format!("Failed to spawn PTY reader thread: {}", e))?;

        // --- PTY writer thread ---
        // Drains the input channel and writes to PTY.
        let mut writer = writer;
        std::thread::Builder::new()
            .name("pty-writer".into())
            .spawn(move || {
                for data in input_rx {
                    if let Err(e) = writer.write_all(&data) {
                        log::warn!("PTY writer error: {}", e);
                        break;
                    }
                }
                log::info!("PTY writer: channel closed, exiting");
            })
            .map_err(|e| format!("Failed to spawn PTY writer thread: {}", e))?;

        // Bump generation so the first get_grid() call produces a real
        // terminal grid (blank with cursor) instead of returning None.
        write_gen.store(1, Ordering::Release);

        Ok(Self {
            state,
            input_tx,
            write_gen,
            render_gen: AtomicU64::new(0),
            alive,
            pty_master: Some(pair.master),
            attached_fd: -1,
            cols,
            rows,
            ws_sink,
            theme: ColorTheme::default(),
            cached_grid: None,
        })
    }

    /// Attach to an existing PTY file descriptor owned by the Java
    /// TerminalSession. Instead of spawning a new shell, this creates a
    /// pipeline that reads from and writes to the given fd.
    ///
    /// The fd is **duplicated** (via `dup()`), so the Java side retains
    /// ownership of the original fd and can continue using it when the
    /// GPU renderer is deactivated. When the pipeline is dropped, only
    /// the duplicated fd is closed.
    ///
    /// # Safety
    /// The caller must ensure `fd` is a valid, open PTY master file descriptor.
    ///
    /// # Arguments
    /// * `fd` - Raw file descriptor of the PTY master (from TerminalSession)
    /// * `cols` - Terminal width in columns
    /// * `rows` - Terminal height in rows
    pub fn attach_fd(fd: RawFd, cols: u16, rows: u16) -> Result<Self, String> {
        log::info!("TerminalPipeline::attach_fd: fd={}, {}x{}", fd, cols, rows);

        if fd < 0 {
            return Err("Invalid file descriptor".to_string());
        }

        // Duplicate the fd so we don't interfere with Java's ownership.
        // When this pipeline is dropped, only our copy is closed.
        let our_fd = unsafe { libc::dup(fd) };
        if our_fd < 0 {
            return Err(format!(
                "Failed to dup fd {}: {}",
                fd,
                std::io::Error::last_os_error()
            ));
        }
        log::info!("TerminalPipeline::attach_fd: dup({}) -> {}", fd, our_fd);

        // Create read and write handles from the duplicated fd.
        // SAFETY: our_fd is a valid fd from dup(). We create two File objects
        // from the same fd. This is safe because:
        // - reads and writes go to the PTY master which supports concurrent access
        // - we dup again for the writer so each File owns its own fd
        let reader_fd = our_fd;
        let writer_fd = unsafe { libc::dup(our_fd) };
        if writer_fd < 0 {
            unsafe { libc::close(our_fd) };
            return Err(format!(
                "Failed to dup fd for writer: {}",
                std::io::Error::last_os_error()
            ));
        }

        let reader = unsafe { std::fs::File::from_raw_fd(reader_fd) };
        let writer = unsafe { std::fs::File::from_raw_fd(writer_fd) };

        // Create the input channel (user input + PtyWrite events)
        let (input_tx, input_rx) = std::sync::mpsc::channel::<Vec<u8>>();

        // Create terminal state
        let state = Arc::new(Mutex::new(TerminalState::new(cols, rows, input_tx.clone())));

        let write_gen = Arc::new(AtomicU64::new(0));
        let alive = Arc::new(AtomicBool::new(true));
        let ws_sink: Arc<Mutex<Option<std::sync::mpsc::Sender<Vec<u8>>>>> =
            Arc::new(Mutex::new(None));

        // --- PTY reader thread ---
        let reader_state = Arc::clone(&state);
        let reader_gen = Arc::clone(&write_gen);
        let reader_alive = Arc::clone(&alive);
        let reader_ws_sink = Arc::clone(&ws_sink);

        std::thread::Builder::new()
            .name("pty-reader-attached".into())
            .spawn(move || {
                let mut reader = reader;
                let mut buf = [0u8; 8192];
                loop {
                    match reader.read(&mut buf) {
                        Ok(0) => {
                            log::info!("PTY reader (attached): EOF");
                            reader_alive.store(false, Ordering::SeqCst);
                            break;
                        }
                        Ok(n) => {
                            let bytes = &buf[..n];
                            if let Ok(mut ts) = reader_state.lock() {
                                ts.process_bytes(bytes);
                            }
                            reader_gen.fetch_add(1, Ordering::Release);

                            if let Ok(guard) = reader_ws_sink.lock() {
                                if let Some(ref tx) = *guard {
                                    let _ = tx.send(bytes.to_vec());
                                }
                            }
                        }
                        Err(ref e) if e.kind() == std::io::ErrorKind::Interrupted => {
                            // EINTR -- retry
                            continue;
                        }
                        Err(e) => {
                            log::error!("PTY reader (attached) error: {}", e);
                            reader_alive.store(false, Ordering::SeqCst);
                            break;
                        }
                    }
                }
            })
            .map_err(|e| format!("Failed to spawn PTY reader thread: {}", e))?;

        // --- PTY writer thread ---
        let mut writer = writer;
        std::thread::Builder::new()
            .name("pty-writer-attached".into())
            .spawn(move || {
                for data in input_rx {
                    if let Err(e) = writer.write_all(&data) {
                        log::warn!("PTY writer (attached) error: {}", e);
                        break;
                    }
                }
                log::info!("PTY writer (attached): channel closed, exiting");
            })
            .map_err(|e| format!("Failed to spawn PTY writer thread: {}", e))?;

        // Bump generation so the first get_grid() call produces a real grid.
        write_gen.store(1, Ordering::Release);

        Ok(Self {
            state,
            input_tx,
            write_gen,
            render_gen: AtomicU64::new(0),
            alive,
            pty_master: None,       // We don't own a portable-pty master
            attached_fd: our_fd,    // Our dup'd fd, for resize via ioctl
            cols,
            rows,
            ws_sink,
            theme: ColorTheme::default(),
            cached_grid: None,
        })
    }

    /// Returns true if this pipeline is attached to an external fd
    /// (Java-owned PTY) rather than owning its own PTY.
    pub fn is_attached(&self) -> bool {
        self.attached_fd >= 0
    }

    /// Check if the terminal is still alive (PTY reader hasn't exited).
    pub fn is_alive(&self) -> bool {
        self.alive.load(Ordering::SeqCst)
    }

    /// Check if the grid has been updated since the last render.
    pub fn is_dirty(&self) -> bool {
        let write = self.write_gen.load(Ordering::Acquire);
        let render = self.render_gen.load(Ordering::Relaxed);
        write > render
    }

    /// Force the pipeline to report dirty on the next `get_grid()` call.
    ///
    /// Used when the renderer is recreated (surface destroy/create cycle)
    /// to ensure the terminal grid is transferred to the new renderer even
    /// if no new PTY output has arrived. Without this, the new renderer
    /// would show a demo grid until the next PTY write.
    pub fn force_dirty(&self) {
        self.write_gen.fetch_add(1, Ordering::Release);
    }

    /// Get the current TermGrid for rendering.
    ///
    /// If the grid is dirty (new PTY output since last call), converts the
    /// alacritty_terminal grid to TermGrid format. If clean, returns the
    /// cached grid.
    ///
    /// Returns None if the terminal state lock is poisoned.
    pub fn get_grid(&mut self) -> Option<&TermGrid> {
        if !self.is_dirty() {
            return self.cached_grid.as_ref();
        }

        // Mark as consumed
        let current_gen = self.write_gen.load(Ordering::Acquire);
        self.render_gen.store(current_gen, Ordering::Relaxed);

        // Lock terminal state and convert grid
        let state = self.state.lock().ok()?;

        // Determine if we need a full rebuild or incremental update
        let needs_full_rebuild = match &mut self.cached_grid {
            Some(ref mut cached) => {
                // Try incremental update; returns None if dimensions changed
                grid_convert::update_grid(cached, &state.term, &self.theme).is_none()
            }
            None => true,
        };

        if needs_full_rebuild {
            self.cached_grid = Some(grid_convert::grid_from_term(&state.term, &self.theme));
        }

        drop(state); // Release lock before returning reference

        self.cached_grid.as_ref()
    }

    /// Send keyboard input to the PTY.
    ///
    /// Converts Android key event parameters to terminal escape sequences
    /// and writes them to the PTY via the input channel.
    pub fn send_key(&self, key_code: i32, unicode_char: i32, meta_state: i32) {
        if let Some(bytes) = input::key_event_to_bytes(key_code, unicode_char, meta_state) {
            if let Err(e) = self.input_tx.send(bytes) {
                log::warn!("Failed to send key input: {}", e);
            }
        }
    }

    /// Send raw bytes to the PTY (for paste, etc.).
    pub fn send_bytes(&self, data: &[u8]) {
        if let Err(e) = self.input_tx.send(data.to_vec()) {
            log::warn!("Failed to send raw input: {}", e);
        }
    }

    /// Resize the terminal.
    ///
    /// Updates the PTY size (sends SIGWINCH to child), the terminal emulator's
    /// internal state, and invalidates the cached grid. The next render will
    /// produce a grid with the new dimensions.
    ///
    /// When attached to an external fd, resize is done via ioctl directly
    /// (the Java side also needs to call setPtyWindowSize for its own state,
    /// but we handle the alacritty_terminal resize here).
    pub fn resize(&mut self, cols: u16, rows: u16) {
        if cols == self.cols && rows == self.rows {
            return;
        }

        log::info!("TerminalPipeline::resize: {}x{} -> {}x{}", self.cols, self.rows, cols, rows);

        self.cols = cols;
        self.rows = rows;

        // Resize the PTY (sends SIGWINCH to the child process)
        if let Some(ref pty_master) = self.pty_master {
            // Owned PTY -- use portable-pty's resize API
            if let Err(e) = pty_master.resize(PtySize {
                rows,
                cols,
                pixel_width: 0,
                pixel_height: 0,
            }) {
                log::error!("Failed to resize PTY: {}", e);
            }
        } else if self.attached_fd >= 0 {
            // Attached fd -- resize via ioctl TIOCSWINSZ directly.
            // Note: when attached, the Java side manages SIGWINCH via its
            // own JNI.setPtyWindowSize(). We only need to resize the
            // alacritty_terminal parser state below. However, we also
            // set the winsize here as a safety measure in case the Java
            // side hasn't resized yet.
            unsafe {
                let ws = libc::winsize {
                    ws_row: rows,
                    ws_col: cols,
                    ws_xpixel: 0,
                    ws_ypixel: 0,
                };
                if libc::ioctl(self.attached_fd, libc::TIOCSWINSZ, &ws) < 0 {
                    log::warn!(
                        "Failed to ioctl TIOCSWINSZ on attached fd {}: {}",
                        self.attached_fd,
                        std::io::Error::last_os_error()
                    );
                }
            }
        }

        // Resize terminal parser state
        if let Ok(mut state) = self.state.lock() {
            state.resize(cols, rows);
        }

        // Invalidate cached grid (dimensions changed)
        self.cached_grid = None;

        // Bump generation to force re-render
        self.write_gen.fetch_add(1, Ordering::Release);
    }

    /// Set a WebSocket output sink for remote access.
    ///
    /// When set, raw PTY bytes are cloned and sent through this channel
    /// in parallel with the native rendering path. Set to None to disconnect.
    pub fn set_ws_sink(&self, sink: Option<std::sync::mpsc::Sender<Vec<u8>>>) {
        if let Ok(mut guard) = self.ws_sink.lock() {
            *guard = sink;
        }
    }

    /// Get the current terminal dimensions.
    pub fn dimensions(&self) -> (u16, u16) {
        (self.cols, self.rows)
    }
}

impl Drop for TerminalPipeline {
    fn drop(&mut self) {
        log::info!(
            "TerminalPipeline: dropping (attached_fd={}, has_pty_master={})",
            self.attached_fd,
            self.pty_master.is_some()
        );
        // Drop order:
        // 1. input_tx dropped -> writer thread exits (channel closed)
        // 2. pty_master dropped -> PTY closed -> reader thread gets EOF
        //    OR attached_fd closed -> reader thread gets EOF/error

        // Close the duplicated attached fd (if any). This does NOT close
        // the original fd owned by Java's TerminalSession.
        if self.attached_fd >= 0 {
            unsafe {
                libc::close(self.attached_fd);
            }
            self.attached_fd = -1;
        }
    }
}
