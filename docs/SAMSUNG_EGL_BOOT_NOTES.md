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
| Early plain `dlopen` monosgen + `libmono-component-marshal-ilgen.so` | `FORCE_LOAD` monosgen (second mapping; fallback/icalls bind wrong copy) |
| After root domain: assembly hook → DNS / SocketAddress / `GetReadDirRBufferSize` icalls | `mono_dllmap_insert` / early CoreLib promote — SIGABRT / `hash != NULL` |
| Dobby `lookup_pinvoke_call_impl` (RVA) → remapped `SystemNative_ReadDirR` | Promoting `ReadDirR` to icall — abort during integrity |
| | Waiter `mono_thread_attach` / `mono_assembly_foreach` — SEGV; Dobby `mono_jit_init*` — soft-lock |

Verified: `icall GetHostEntryForName rc=0`, `#HOTFIX_CHECKBUNDLEEND`, clean `#HOTFIX_END` (no `Integrity check failed: SystemNative_ReadDirR`), MonoGameState + login.
