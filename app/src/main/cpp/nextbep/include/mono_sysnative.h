#pragma once
#include <filesystem>

// Remap game libSystem.Native.so to a unique SONAME so Mono HotFix DNS can
// bind it without colliding with CoreCLR's already-loaded copy.
void mono_sysnative_prepare(const std::filesystem::path &gameLibsPath,
                            const std::filesystem::path &codeCache);

// Install dlopen hooks (Mono callers only). Call AFTER Unity+il2cpp load.
extern "C" void bepinex_enable_mono_native_redirect(void);
