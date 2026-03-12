//! codefactory-android: JNI bridge for wgpu GPU-accelerated terminal rendering on Android.
//!
//! This crate provides the native side of the SurfaceView <-> Rust wgpu pipeline.
//! It receives an ANativeWindow handle from Java via JNI, creates a wgpu Surface,
//! and renders terminal text using a glyph atlas and instanced draw calls.
//!
//! Architecture:
//! - `atlas`: CPU-side glyph rasterization (fontdue) + texture atlas management
//! - `colors`: Terminal color theme (ANSI 16, 256 indexed, 24-bit true color)
//! - `grid`: Terminal cell grid data structure (interface to alacritty_terminal)
//! - `grid_convert`: Bridge from alacritty_terminal Grid<Cell> to TermGrid (feature-gated)
//! - `input`: Android key event -> terminal escape sequence conversion
//! - `pipeline`: PTY -> alacritty parser -> renderer orchestration (feature-gated)
//! - `renderer`: wgpu render pipelines (background + glyph), instanced rendering
//! - `surface`: Android ANativeWindow + raw-window-handle for wgpu

mod atlas;
mod colors;
mod grid;
#[cfg(feature = "terminal-pipeline")]
mod grid_convert;
mod input;
#[cfg(feature = "terminal-pipeline")]
mod pipeline;
mod renderer;
mod surface;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jfloat, jint};
use jni::JNIEnv;
use std::panic;
use std::sync::Mutex;

#[cfg(feature = "terminal-pipeline")]
use pipeline::TerminalPipeline;
use renderer::Renderer;

/// Global renderer state, protected by a mutex.
/// In a real app this would be per-activity, but for the spike a global is fine.
static RENDERER: Mutex<Option<Renderer>> = Mutex::new(None);

/// Global terminal pipeline state.
#[cfg(feature = "terminal-pipeline")]
static PIPELINE: Mutex<Option<TerminalPipeline>> = Mutex::new(None);

/// Lock a mutex, recovering from poison (a prior panic while holding the lock).
/// This is necessary because static mutexes persist across Android activity
/// recreations within the same process. If any JNI call panics while holding
/// the lock, the mutex stays poisoned forever, bricking all subsequent calls.
fn lock_or_recover<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| {
        log::warn!("Recovering from poisoned mutex");
        poisoned.into_inner()
    })
}

/// Whether the native library initialized successfully.
/// Checked by Java side via nativeIsAvailable().
static INIT_OK: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

// ---------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------
// Java class: com.termux.app.codefactory.CodefactoryBridge
// Method signatures must match the Java native declarations exactly.
//
// SAFETY: All JNI entry points are wrapped in catch_unwind to prevent Rust
// panics from unwinding into the JVM, which would cause an immediate crash.
// If a panic occurs, it is logged and the function returns gracefully.

/// Called once when the library is loaded. Initializes Android logging.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let result = panic::catch_unwind(|| {
        // Initialize android logger so log::info! etc. show up in logcat.
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Debug)
                .with_tag("CodefactoryNative"),
        );
        log::info!("nativeInit: codefactory native library loaded");
        log::info!(
            "nativeInit: terminal-pipeline feature {}",
            if cfg!(feature = "terminal-pipeline") {
                "ENABLED"
            } else {
                "DISABLED (renderer-only mode)"
            }
        );
        INIT_OK.store(true, std::sync::atomic::Ordering::SeqCst);
    });

    match result {
        Ok(()) => 0, // success
        Err(_) => {
            // Panic occurred during init -- can't use log since logger may not
            // be initialized. Return error code.
            -1
        }
    }
}

/// Called from SurfaceHolder.Callback.surfaceCreated.
/// Receives the Android Surface object, extracts ANativeWindow, creates wgpu surface + device.
///
/// On recreation (e.g., after the app was backgrounded), this creates a completely
/// fresh wgpu Instance, Surface, Device, and Queue. The existing TerminalPipeline
/// (if any) is preserved -- only the rendering surface changes. The pipeline's
/// current grid is immediately transferred to the new renderer so the terminal
/// content is visible on the first frame.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeSurfaceCreated<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    surface: JObject<'local>,
) {
    // catch_unwind requires FnOnce: Send, but JNIEnv is !Send.
    // We use AssertUnwindSafe since we handle the panic at the boundary.
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        log::info!("nativeSurfaceCreated: creating wgpu surface and terminal renderer");

        // Drop any stale renderer first to release old wgpu/Vulkan resources
        // before creating new ones. This is critical on Android where the old
        // ANativeWindow is already invalid by the time surfaceCreated is called
        // for the new surface.
        {
            let mut guard = lock_or_recover(&RENDERER);
            if guard.is_some() {
                log::warn!("nativeSurfaceCreated: dropping stale renderer from previous surface");
                *guard = None;
            }
        }

        match Renderer::new(&env, &surface) {
            Ok(mut renderer) => {
                // Transfer the existing pipeline's grid to the new renderer
                // immediately, so the terminal content is visible on the first
                // frame without waiting for new PTY output.
                #[cfg(feature = "terminal-pipeline")]
                {
                    let mut pipeline_guard = lock_or_recover(&PIPELINE);
                    if let Some(ref mut pipeline) = *pipeline_guard {
                        log::info!(
                            "nativeSurfaceCreated: reconnecting to existing pipeline (alive={})",
                            pipeline.is_alive()
                        );
                        // Force the pipeline to report dirty so get_grid()
                        // performs a fresh grid conversion even if no new PTY
                        // output arrived while the surface was destroyed.
                        pipeline.force_dirty();
                        if let Some(grid) = pipeline.get_grid() {
                            renderer.set_grid(grid.clone());
                            log::info!(
                                "nativeSurfaceCreated: transferred grid {}x{} from pipeline",
                                grid.cols,
                                grid.rows
                            );
                        }
                    } else {
                        log::info!("nativeSurfaceCreated: no pipeline active, using demo grid");
                    }
                    drop(pipeline_guard);
                }

                let mut guard = lock_or_recover(&RENDERER);
                *guard = Some(renderer);
                log::info!("nativeSurfaceCreated: renderer created successfully");
            }
            Err(e) => {
                log::error!("nativeSurfaceCreated: failed to create renderer: {}", e);
            }
        }
    }));

    if let Err(e) = result {
        log::error!(
            "nativeSurfaceCreated: PANIC caught: {:?}",
            panic_message(&e)
        );
    }
}

/// Called from SurfaceHolder.Callback.surfaceChanged.
/// Reconfigures the wgpu surface with the new dimensions and resizes the terminal.
///
/// NOTE: The Java side passes (Surface, int, int) but we only need (int, int) here
/// since the wgpu surface was already created in nativeSurfaceCreated. The Surface
/// parameter is included to match the Java declaration.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeSurfaceChanged<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    _surface: JObject<'local>,
    width: jint,
    height: jint,
) {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        log::info!("nativeSurfaceChanged: {}x{}", width, height);

        let mut renderer_guard = lock_or_recover(&RENDERER);
        if let Some(ref mut renderer) = *renderer_guard {
            renderer.resize(width as u32, height as u32);

            #[cfg(feature = "terminal-pipeline")]
            {
                // Compute new terminal grid dimensions from surface size and cell metrics
                let (cell_w, cell_h) = renderer.cell_size();
                let cols = (width as f32 / cell_w).floor() as u16;
                let rows = (height as f32 / cell_h).floor() as u16;
                let cols = cols.max(1);
                let rows = rows.max(1);

                // Resize the terminal pipeline to match
                let mut pipeline_guard = lock_or_recover(&PIPELINE);
                if let Some(ref mut pipeline) = *pipeline_guard {
                    pipeline.resize(cols, rows);
                }
                drop(pipeline_guard);
            }

            // Render a frame immediately after resize
            render_frame(renderer);
        } else {
            log::warn!("nativeSurfaceChanged: no renderer available");
        }
    }));

    if let Err(e) = result {
        log::error!(
            "nativeSurfaceChanged: PANIC caught: {:?}",
            panic_message(&e)
        );
    }
}

/// Called from SurfaceHolder.Callback.surfaceDestroyed.
/// Drops the wgpu Surface, Device, Queue and releases the ANativeWindow reference.
///
/// The TerminalPipeline is intentionally preserved -- the shell process continues
/// running and accumulating output while the surface is gone. When a new surface
/// is created (nativeSurfaceCreated), the pipeline's grid is transferred to the
/// new renderer immediately.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeSurfaceDestroyed(
    _env: JNIEnv,
    _class: JClass,
) {
    let result = panic::catch_unwind(|| {
        log::info!("nativeSurfaceDestroyed: dropping renderer (pipeline preserved)");

        // Drop the renderer. This releases:
        // - wgpu Surface, Device, Queue (Vulkan resources)
        // - AndroidSurface (calls ANativeWindow_release)
        // - All GPU buffers, textures, pipelines
        {
            let mut guard = lock_or_recover(&RENDERER);
            if guard.is_some() {
                *guard = None;
                log::info!("nativeSurfaceDestroyed: renderer dropped successfully");
            } else {
                log::warn!("nativeSurfaceDestroyed: no renderer to drop (already None)");
            }
        }

        // Log pipeline status for debugging surface lifecycle
        #[cfg(feature = "terminal-pipeline")]
        {
            let guard = lock_or_recover(&PIPELINE);
            if let Some(ref pipeline) = *guard {
                log::info!(
                    "nativeSurfaceDestroyed: pipeline preserved (alive={}, attached={})",
                    pipeline.is_alive(),
                    pipeline.is_attached()
                );
            } else {
                log::info!("nativeSurfaceDestroyed: no pipeline active");
            }
        }
    });

    if let Err(e) = result {
        log::error!(
            "nativeSurfaceDestroyed: PANIC caught: {:?}",
            panic_message(&e)
        );
    }
}

/// Called from SurfaceView.onTouchEvent.
/// action: MotionEvent action (ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, etc.)
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeTouchEvent(
    _env: JNIEnv,
    _class: JClass,
    action: jint,
    x: jfloat,
    y: jfloat,
) {
    let _ = panic::catch_unwind(|| {
        log::debug!("nativeTouchEvent: action={}, x={}, y={}", action, x, y);
        // TODO: Map touch coordinates to terminal grid (col, row) for mouse
        // reporting and text selection.
    });
}

/// Called from Activity key event forwarding.
/// Converts the Android key event to terminal input and writes to PTY.
///
/// keyCode: Android KeyEvent.getKeyCode()
/// unicode_char: KeyEvent.getUnicodeChar() (with meta state applied)
/// meta_state: KeyEvent.getMetaState()
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeKeyEvent(
    _env: JNIEnv,
    _class: JClass,
    key_code: jint,
    unicode_char: jint,
    meta_state: jint,
) {
    let _ = panic::catch_unwind(|| {
        log::debug!(
            "nativeKeyEvent: keyCode={}, unicodeChar={}, metaState={}",
            key_code,
            unicode_char,
            meta_state
        );

        #[cfg(feature = "terminal-pipeline")]
        {
            let guard = lock_or_recover(&PIPELINE);
            if let Some(ref pipeline) = *guard {
                pipeline.send_key(key_code, unicode_char, meta_state);
            }
        }
    });
}

/// Explicitly request a frame render. Called from the Java side when
/// the surface is ready and we want to display something.
///
/// This is the hot path: checks the pipeline dirty flag, converts the
/// alacritty grid to TermGrid if needed, and renders via wgpu.
/// If nothing changed since the last frame, the render is skipped
/// (battery optimization).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeRenderFrame(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = panic::catch_unwind(|| {
        let mut renderer_guard = lock_or_recover(&RENDERER);
        if let Some(ref mut renderer) = *renderer_guard {
            render_frame(renderer);
        }
    });
}

/// Spawn the terminal pipeline (PTY + parser).
///
/// Called from Java after the surface is created and sized. The terminal
/// begins running immediately; output will appear on the next render frame.
///
/// shell: optional shell command (null = default SHELL env var)
/// cols: terminal width in columns
/// rows: terminal height in rows
///
/// Returns 0 on success, -1 on failure, -2 if terminal-pipeline feature is disabled.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeSpawnTerminal<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    shell: JString<'local>,
    cols: jint,
    rows: jint,
) -> jint {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        log::info!("nativeSpawnTerminal: cols={}, rows={}", cols, rows);

        #[cfg(feature = "terminal-pipeline")]
        {
            let shell_str: Option<String> = if shell.is_null() {
                None
            } else {
                env.get_string(&shell).ok().map(|s| s.into())
            };

            let shell_ref = shell_str.as_deref();

            match TerminalPipeline::spawn(cols as u16, rows as u16, shell_ref) {
                Ok(pipeline) => {
                    let mut guard = lock_or_recover(&PIPELINE);
                    *guard = Some(pipeline);
                    log::info!("nativeSpawnTerminal: pipeline created successfully");
                    0
                }
                Err(e) => {
                    log::error!("nativeSpawnTerminal: failed to spawn terminal: {}", e);
                    -1
                }
            }
        }

        #[cfg(not(feature = "terminal-pipeline"))]
        {
            let _ = (shell, cols, rows, &mut env);
            log::warn!("nativeSpawnTerminal: terminal-pipeline feature is disabled");
            -2
        }
    }));

    match result {
        Ok(code) => code,
        Err(e) => {
            log::error!(
                "nativeSpawnTerminal: PANIC caught: {:?}",
                panic_message(&e)
            );
            -1
        }
    }
}

/// Send raw text input to the terminal (e.g., from a soft keyboard or paste).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeSendInput<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) {
    let _ = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        if text.is_null() {
            return;
        }

        #[cfg(feature = "terminal-pipeline")]
        {
            if let Ok(input_str) = env.get_string(&text) {
                let s: String = input_str.into();
                let guard = lock_or_recover(&PIPELINE);
                if let Some(ref pipeline) = *guard {
                    pipeline.send_bytes(s.as_bytes());
                }
            }
        }

        #[cfg(not(feature = "terminal-pipeline"))]
        {
            let _ = (&mut env, &text);
            log::trace!("nativeSendInput: terminal-pipeline feature is disabled");
        }
    }));
}

/// Check if the terminal pipeline is still alive.
/// Returns 1 if alive, 0 if the shell has exited or pipeline is unavailable.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeIsAlive(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let result = panic::catch_unwind(|| {
        #[cfg(feature = "terminal-pipeline")]
        {
            let guard = lock_or_recover(&PIPELINE);
            match *guard {
                Some(ref pipeline) => {
                    if pipeline.is_alive() {
                        1
                    } else {
                        0
                    }
                }
                None => 0,
            }
        }

        #[cfg(not(feature = "terminal-pipeline"))]
        {
            0
        }
    });

    result.unwrap_or(0)
}

/// Attach the GPU renderer pipeline to an existing PTY file descriptor
/// owned by the Java TerminalSession. The fd is duplicated internally,
/// so the Java side retains full ownership of the original.
///
/// This allows the GPU renderer to share the same terminal session as
/// the classic Java renderer. When toggling back to classic, call
/// nativeDetachPty() to shut down the Rust reader/writer threads and
/// let the Java side resume exclusive PTY access.
///
/// fd: the PTY master file descriptor from TerminalSession
/// cols: terminal width in columns
/// rows: terminal height in rows
///
/// Returns 0 on success, -1 on failure, -2 if feature disabled.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeAttachPtyFd(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
    cols: jint,
    rows: jint,
) -> jint {
    let result = panic::catch_unwind(|| {
        log::info!("nativeAttachPtyFd: fd={}, cols={}, rows={}", fd, cols, rows);

        #[cfg(feature = "terminal-pipeline")]
        {
            // Drop any existing pipeline first
            {
                let mut guard = lock_or_recover(&PIPELINE);
                if guard.is_some() {
                    log::info!("nativeAttachPtyFd: dropping existing pipeline");
                    *guard = None;
                }
            }

            match TerminalPipeline::attach_fd(fd, cols as u16, rows as u16) {
                Ok(pipeline) => {
                    let mut guard = lock_or_recover(&PIPELINE);
                    *guard = Some(pipeline);
                    log::info!("nativeAttachPtyFd: pipeline attached successfully");
                    0
                }
                Err(e) => {
                    log::error!("nativeAttachPtyFd: failed to attach: {}", e);
                    -1
                }
            }
        }

        #[cfg(not(feature = "terminal-pipeline"))]
        {
            let _ = (fd, cols, rows);
            log::warn!("nativeAttachPtyFd: terminal-pipeline feature is disabled");
            -2
        }
    });

    match result {
        Ok(code) => code,
        Err(e) => {
            log::error!(
                "nativeAttachPtyFd: PANIC caught: {:?}",
                panic_message(&e)
            );
            -1
        }
    }
}

/// Detach the GPU renderer pipeline from the shared PTY.
///
/// Drops the pipeline (closing the duplicated fd and stopping the
/// reader/writer threads), allowing the Java TerminalSession to resume
/// exclusive PTY access. This is called when toggling from GPU back to
/// the classic renderer.
///
/// Returns 0 on success, -1 on error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeDetachPty(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let result = panic::catch_unwind(|| {
        log::info!("nativeDetachPty: detaching pipeline");

        #[cfg(feature = "terminal-pipeline")]
        {
            let mut guard = lock_or_recover(&PIPELINE);
            if let Some(ref pipeline) = *guard {
                if pipeline.is_attached() {
                    log::info!("nativeDetachPty: dropping attached pipeline");
                    *guard = None;
                    0
                } else {
                    log::info!("nativeDetachPty: pipeline is not attached (owns its own PTY), not dropping");
                    0
                }
            } else {
                log::info!("nativeDetachPty: no pipeline to detach");
                0
            }
        }

        #[cfg(not(feature = "terminal-pipeline"))]
        {
            0
        }
    });

    match result {
        Ok(code) => code,
        Err(e) => {
            log::error!(
                "nativeDetachPty: PANIC caught: {:?}",
                panic_message(&e)
            );
            -1
        }
    }
}

// ---------------------------------------------------------------------------
// Backend lifecycle (Axum server stubs)
// ---------------------------------------------------------------------------

/// Start the codefactory backend (Axum HTTP server).
///
/// This is a stub that logs the request and returns success. The actual Axum
/// server integration will be implemented later.
///
/// home_path: the Termux home directory (for config, logs, sockets)
/// port: the port to bind on (typically 3001)
///
/// Returns 0 on success, -1 on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeStartBackend<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    home_path: JString<'local>,
    port: jint,
) -> jint {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let home_str: String = if home_path.is_null() {
            "<null>".to_string()
        } else {
            env.get_string(&home_path)
                .map(|s| s.into())
                .unwrap_or_else(|_| "<invalid>".to_string())
        };

        log::info!(
            "nativeStartBackend: backend start requested (home={}, port={})",
            home_str,
            port
        );

        // Stub: return success. Actual Axum server start comes later.
        0
    }));

    match result {
        Ok(code) => code,
        Err(e) => {
            log::error!(
                "nativeStartBackend: PANIC caught: {:?}",
                panic_message(&e)
            );
            -1
        }
    }
}

/// Stop the codefactory backend gracefully.
///
/// This is a stub that logs the request. The actual shutdown logic will be
/// implemented when the Axum server integration is added.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeStopBackend(
    _env: JNIEnv,
    _class: JClass,
) {
    let result = panic::catch_unwind(|| {
        log::info!("nativeStopBackend: backend stop requested");
    });

    if let Err(e) = result {
        log::error!(
            "nativeStopBackend: PANIC caught: {:?}",
            panic_message(&e)
        );
    }
}

/// Check if the backend is ready to accept HTTP requests.
///
/// This is a stub that always returns 1 (ready). The actual readiness check
/// will query the Axum server state when integrated.
///
/// Returns 1 if ready, 0 otherwise.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeIsBackendReady(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let result = panic::catch_unwind(|| {
        log::debug!("nativeIsBackendReady: returning true (stub)");
        1
    });

    result.unwrap_or(0)
}

// ---------------------------------------------------------------------------
// DeX support: mouse events and terminal resize
// ---------------------------------------------------------------------------

/// Forward a mouse event from the Java side.
///
/// This is called by DeXInputHandler when a mouse event occurs in the
/// CodefactorySurfaceView. The Java side has already computed the terminal
/// cell coordinates from pixel positions.
///
/// event_type: 0=press, 1=release, 2=move, 3=scroll
/// button: 0=left, 1=middle, 2=right, 3=none (for move)
/// pixel_x/y: position in surface pixels
/// col/row: terminal cell coordinates (0-based)
/// scroll_delta: for scroll events, positive=up, negative=down
///
/// TODO: Full implementation should:
/// 1. Check if the terminal has mouse reporting enabled (via alacritty_terminal mode flags)
/// 2. Generate the appropriate escape sequence (SGR, X10, etc.)
/// 3. Write the sequence to the PTY
/// 4. For selection: track start/end positions, highlight cells in renderer
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeMouseEvent(
    _env: JNIEnv,
    _class: JClass,
    event_type: jint,
    button: jint,
    pixel_x: jfloat,
    pixel_y: jfloat,
    col: jint,
    row: jint,
    scroll_delta: jfloat,
) {
    let _ = panic::catch_unwind(|| {
        log::debug!(
            "nativeMouseEvent: type={} btn={} px=({},{}) cell=({},{}) scroll={}",
            event_type, button, pixel_x, pixel_y, col, row, scroll_delta
        );

        #[cfg(feature = "terminal-pipeline")]
        {
            let guard = lock_or_recover(&PIPELINE);
            if let Some(ref pipeline) = *guard {
                // For now, mouse events from DeX are handled on the Java side
                // by generating SGR escape sequences directly via sendInput().
                // When the Rust side has full mouse mode tracking, this will
                // generate the sequences here instead.
                let _ = pipeline;
            }
        }
    });
}

/// Resize the terminal grid to new dimensions.
///
/// Called from the Java side after debouncing rapid resize events (e.g.,
/// during DeX drag-resize). This resizes the alacritty_terminal grid and
/// sends SIGWINCH to the PTY process.
///
/// cols: new terminal width in columns
/// rows: new terminal height in rows
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termux_app_codefactory_CodefactoryBridge_nativeResizeTerminal(
    _env: JNIEnv,
    _class: JClass,
    cols: jint,
    rows: jint,
) {
    let _ = panic::catch_unwind(|| {
        log::info!("nativeResizeTerminal: {}x{}", cols, rows);

        #[cfg(feature = "terminal-pipeline")]
        {
            let mut pipeline_guard = lock_or_recover(&PIPELINE);
            if let Some(ref mut pipeline) = *pipeline_guard {
                pipeline.resize(cols as u16, rows as u16);
                log::info!("nativeResizeTerminal: pipeline resized to {}x{}", cols, rows);
            } else {
                log::warn!("nativeResizeTerminal: no pipeline to resize");
            }
        }

        #[cfg(not(feature = "terminal-pipeline"))]
        {
            let _ = (cols, rows);
            log::trace!("nativeResizeTerminal: terminal-pipeline feature is disabled");
        }
    });
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/// Extract a human-readable message from a panic payload.
fn panic_message(e: &Box<dyn std::any::Any + Send>) -> &str {
    e.downcast_ref::<String>()
        .map(|s| s.as_str())
        .or_else(|| e.downcast_ref::<&str>().copied())
        .unwrap_or("unknown panic")
}

/// Render a single frame, pulling the latest grid from the pipeline.
///
/// If a pipeline is active and has new output, converts the alacritty grid
/// to TermGrid and hands it to the renderer. If no pipeline is active,
/// renders the demo grid (static content).
fn render_frame(renderer: &mut Renderer) {
    // Try to get updated grid from pipeline (only if feature is enabled)
    #[cfg(feature = "terminal-pipeline")]
    {
        let mut pipeline_guard = lock_or_recover(&PIPELINE);
        if let Some(ref mut pipeline) = *pipeline_guard {
            if let Some(grid) = pipeline.get_grid() {
                renderer.set_grid(grid.clone());
            }
        }
        drop(pipeline_guard);
    }

    if let Err(e) = renderer.render() {
        log::error!("render_frame: render failed: {}", e);
    }
}
