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

Wired in `mono_sysnative.cpp`.

| Do | Do not |
|---|---|
| Remap + `FORCE_LOAD` `libXdtSysNative.so` | `DobbyHook` libc `dlopen` — THEMIS SIGTRAP |
| After monosgen maps + root domain: `mono_install_assembly_load_hook` | `mono_dllmap_insert` / assembly hook before domain — SIGABRT |
| Promote every `Interop/Sys` P/Invoke → `mono_dangerous_add_raw_internal_call` + remapped `SystemNative_*` | Waiter `mono_thread_attach` / `mono_assembly_foreach` — SEGV |
| | Dobby `mono_jit_init*` — soft-lock; exported `mono_compile_method` — not on JIT trampoline path |

Verified: `icall GetHostEntryForName rc=0`, `#HOTFIX_CHECKBUNDLEEND`, no `InvalidProgramException` / DnsClient UDP/53 fallback.
