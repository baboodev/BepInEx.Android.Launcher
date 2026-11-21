# Samsung SM-S938B boot — merged conclusion (Asia/Bangkok)

Branch: `heartopia-samsung-boot` (from `880c950` + known-good boot core).

## What actually blocked launch

| Needed (keep) | Broke GLES after Chainloader (disable) |
|---|---|
| FusionConfig host ClassLoader / `getRuntimeConfig` | EGL GOT hooks (`eglInitialize` / `eglChooseConfig`) |
| `ensureExtractedNativeLibs` for `extractNativeLibs=false` | Early public/vendor EGL preload |
| Early `libthemis-v8` preload (il2cpp decrypt) | `ensureGraphicsLinkerNamespace` / sphal ClassLoader link |
| `libmimalloc` RTLD_GLOBAL preload (DataCenter) | `install_cmdline_spoof` before Themis (AdrenoVK poison) |
| UnityVersionLookup hashed-asset scan | Vendor GL `dlopen` via sphal |

## Merged shape

- Keep Heartopia Java: extract libs, config ClassLoader, Unity version, GameDetector splits, OAuth helpers.
- Replace `fusion.cpp` boot path with the bisect-proven minimal path (themis + mimalloc only; no EGL/sphal/cmdline).
- Skip sphal ClassLoader link in `GameRuntime`.

Verified vanilla (no Bugtopia): Product Name, Chainloader complete, login bridge, GLES Adreno, process alive.
Remaining: Mono `System.Native` SONAME remap for HotFix DNS (still in tree as `soname.cpp`; wire carefully without reintroducing early dlopen hooks).
