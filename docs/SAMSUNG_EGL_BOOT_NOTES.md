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

## System.Native / HotFix DNS (follow-up)

Wired in `mono_sysnative.cpp` (unique SONAME `libXdtSysNative.so` + `mono_dl_fallback_register`).

| Do | Do not |
|---|---|
| Remap + `FORCE_LOAD` remapped copy | `DobbyHook` libc `dlopen` — THEMIS SIGTRAP via coreclr |
| `mono_dl_fallback_register` after monosgen loads | `mono_dllmap_insert` from `jit_init` — SIGABRT on this Mono |
| `install_themis_f000000a_guard` after themis preload | Early monosgen preload + jit_init Dobby |

HotFix still logs `GetHostEntryForName` InvalidProgramException and falls back to the oversea pool; version HTTP fetch succeeds. Full bare-soname redirect needs a themis-safe path (not libc Dobby).
