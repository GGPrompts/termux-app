//! Android surface handling: extract ANativeWindow from a Java Surface object
//! and provide a raw-window-handle implementation for wgpu.

use jni::objects::JObject;
use jni::JNIEnv;
use raw_window_handle::{
    AndroidDisplayHandle, AndroidNdkWindowHandle, HasDisplayHandle, HasWindowHandle,
    RawDisplayHandle, RawWindowHandle,
};
use std::ptr::NonNull;

/// Wraps an ANativeWindow pointer for use with wgpu.
/// Implements HasWindowHandle and HasDisplayHandle traits required by wgpu.
pub struct AndroidSurface {
    /// Pointer to the ANativeWindow. We own a reference (acquired via ANativeWindow_fromSurface).
    native_window: NonNull<ndk_sys::ANativeWindow>,
}

// SAFETY: ANativeWindow is a thread-safe refcounted object in Android NDK.
// We acquire a reference in from_java_surface() and release it in Drop.
// The pointer itself can safely be sent between threads.
unsafe impl Send for AndroidSurface {}
unsafe impl Sync for AndroidSurface {}

impl AndroidSurface {
    /// Create an AndroidSurface from a Java Surface object.
    ///
    /// # Safety
    /// The JNIEnv and Surface object must be valid. The returned AndroidSurface
    /// holds an ANativeWindow reference that must be released (via Drop).
    pub fn from_java_surface(env: &JNIEnv, surface: &JObject) -> Result<Self, String> {
        // Get the raw JNI pointers needed by ndk_sys::ANativeWindow_fromSurface
        let raw_env = env.get_raw();
        let raw_surface = surface.as_raw();

        let native_window = unsafe {
            ndk_sys::ANativeWindow_fromSurface(raw_env as *mut _, raw_surface as *mut _)
        };

        let native_window = NonNull::new(native_window)
            .ok_or_else(|| "ANativeWindow_fromSurface returned null".to_string())?;

        log::info!(
            "AndroidSurface: acquired ANativeWindow {:?}",
            native_window.as_ptr()
        );

        Ok(Self { native_window })
    }

    /// Get the raw ANativeWindow pointer.
    #[allow(dead_code)]
    pub fn native_window_ptr(&self) -> *mut ndk_sys::ANativeWindow {
        self.native_window.as_ptr()
    }
}

impl Drop for AndroidSurface {
    fn drop(&mut self) {
        log::info!(
            "AndroidSurface: releasing ANativeWindow {:?}",
            self.native_window.as_ptr()
        );
        unsafe {
            ndk_sys::ANativeWindow_release(self.native_window.as_ptr());
        }
    }
}

// raw-window-handle implementations required by wgpu::Instance::create_surface()

impl HasWindowHandle for AndroidSurface {
    fn window_handle(
        &self,
    ) -> Result<raw_window_handle::WindowHandle<'_>, raw_window_handle::HandleError> {
        let handle = AndroidNdkWindowHandle::new(self.native_window.cast());
        let raw = RawWindowHandle::AndroidNdk(handle);
        // SAFETY: the native window pointer is valid for the lifetime of self
        Ok(unsafe { raw_window_handle::WindowHandle::borrow_raw(raw) })
    }
}

impl HasDisplayHandle for AndroidSurface {
    fn display_handle(
        &self,
    ) -> Result<raw_window_handle::DisplayHandle<'_>, raw_window_handle::HandleError> {
        let handle = AndroidDisplayHandle::new();
        let raw = RawDisplayHandle::Android(handle);
        // SAFETY: Android display handle has no pointer, always valid
        Ok(unsafe { raw_window_handle::DisplayHandle::borrow_raw(raw) })
    }
}
