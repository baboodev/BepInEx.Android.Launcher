# Samsung SM-S938B (API 37) — EGL boot failure notes

Paused 2026-11-21 (Asia/Bangkok). Resume with `adb -s 192.168.0.123:5555` (same device as USB `R5CY22AFMWM`).

## Symptom

`com.bepinex.android:game` reaches Chainloader + `Product Name: Heartopia`, then dies:

1. `eglInitialize(dpy) ok=1 live=1` (our hook)
2. `eglChooseConfig` → `EGL_NOT_INITIALIZED` (0x3001), every retry
3. Unity: `Unable to find a configuration matching minimum spec!`
4. Themis `SIGTRAP` on UnityMain (~2s later)

Package stays `com.bepinex.android`. Last tested build: **ci.71 / ci.72** (GOT hooks; force-vulkan removed).

## Important user fact (do not ignore)

**Early launcher builds without the Bugtopia mod used to start the game on this same device.**

Implication: stock Heartopia + an older NextBep launcher path was viable. The regression is likely in launcher-side changes stacked for Samsung (sphal / ClassLoader namespace / early themis / EGL hooks / cmdline), not “Adreno cannot run Heartopia at all”.

## What was tried (newest last)

| Step | Change | Result |
|------|--------|--------|
| Upgrade launcher | ci.17 → ci.55+ (mono_dllmap abort) | Abort gone; EGL remains |
| ClassLoader ns | `createClassloaderNamespace` + sphal once-guard | Vendor GLES loads; still NOT_INITIALIZED |
| sphal dlopen | Route vendor + public `libEGL`/`libGLES*` via sphal | Libs load; dual-stack suspected |
| force-vulkan | Cmdline spoof + Intent `unity=-force-vulkan` | Unity still GLES; later removed |
| Dobby `eglInitialize` | Far trampoline on system libEGL | Install logged; often did not intercept / lied `ok=1` while libEGL logged fail |
| GOT patch | `eglInitialize` / `eglChooseConfig` / GetPlatformDisplay on `libunity.so` | Hooks fire; chooseConfig still 0x3001 after terminate+reinit |
| Public sonames off sphal | Only vendor/`eglSubDriver`/`*_adreno` via sphal; public via default dlopen | Still ok=1 live=1 + chooseConfig NOT_INITIALIZED |
| Remove force-vulkan | spoof_cmdline + BootstrapActivity | No change on ci.71 |

## Current code state (uncommitted in launcher tree)

- `fusion.cpp`: GOT-only EGL quirk hooks; chooseConfig reinit+retry; public EGL not forced through sphal; `g_orig` must be real `libEGL` (dlsym / dladdr), never PLT stub
- `spoof_cmdline.cpp`: package-only fake cmdline (no `-force-vulkan`)
- `BootstrapActivity.kt`: no `-force-vulkan` Intent extra
- APK staged at Heartopia-Helper `out/android-launcher/Bugtopia-Launcher-debug.apk` when last built

## Strong hypotheses for tomorrow

1. **Bisect early “works without mod” launcher APK vs current**  
   Diff: themis early preload, sphal redirect of public EGL, `ensureGraphicsLinkerNamespace`, EGL hooks, cmdline spoof. First goal: reproduce boot **without** Bugtopia plugins on a known-good old APK, then on current with modpack empty / plugins removed.

2. **Early `libthemis-v8` preload before GLES**  
   Stock loads Themis after GLES. We preload Themis for il2cpp decrypt → AdrenoVK may poison GLES (`eglInitialize` looks live, `eglChooseConfig` stays NOT_INITIALIZED). Previous ICD-block attempt stopped VK but still failed — re-check with a launcher that did **not** early-preload Themis if that old build existed.

3. **Dual EGL / wrong `g_orig`**  
   `ok=1 live=1` while chooseConfig always 0x3001 even after terminate+init on the same `dpy` points to split stacks or calling a non-real initialize. ci.72 started preferring GOT→libEGL via `dladdr`; verify that path on device.

4. **Do not keep piling hooks** until bisect proves which launcher revision last booted clean GLES on SM-S938B.

## Resume checklist

```text
adb connect 192.168.0.123:5555
# 1) Install oldest available Bugtopia/NextBep launcher APK that user remembers working (no mod / empty plugins)
# 2) Confirm game draws past Unity EGL
# 3) Install current ci.7x with empty modpack — isolate mod vs launcher
# 4) Diff fusion.cpp / GameRuntime / NativeLibraryManager / themis preload vs that old tree
```

Device: Samsung SM-S938B, API 37, `ro.hardware.egl=adreno`. Tablet QX1000 API 35 must stay unbroken (no-op when first GLES init already succeeds).
