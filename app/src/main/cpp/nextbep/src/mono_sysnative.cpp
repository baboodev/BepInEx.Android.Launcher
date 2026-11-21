#include <mono_sysnative.h>

#include <android/dlext.h>
#include <atomic>
#include <cstring>
#include <dlfcn.h>
#include <pthread.h>
#include <string>
#include <unistd.h>

#include <logger.h>
#include <utilities/soname.h>

#define TAG "BepInExNative"

namespace fs = std::filesystem;

// Remap game libSystem.Native.so → unique SONAME so it can coexist with
// CoreCLR's copy. Do NOT DobbyHook libc dlopen (THEMIS SIGTRAP) and do NOT
// call mono_dllmap_insert from jit_init (SIGABRT on this Mono). Only register
// mono_dl_fallback; HotFix DNS may still hit InvalidProgramException and fall
// back to the oversea pool — same as the known-good Samsung boot without remap.

static std::string g_redirectSystemNative;
static std::atomic<bool> g_fallbackRegistered{false};
static std::atomic<bool> g_waiterStarted{false};

static void *dlopen_force(const char *path, int flags)
{
    android_dlextinfo ext;
    std::memset(&ext, 0, sizeof(ext));
    ext.flags = ANDROID_DLEXT_FORCE_LOAD;
    void *h = android_dlopen_ext(path, flags, &ext);
    if (!h) {
        h = dlopen(path, flags);
    }
    return h;
}

using MonoDlFallbackLoad = void *(*)(const char *name, int flags, char **err, void *user_data);
using MonoDlFallbackSymbol = void *(*)(void *handle, const char *name, char **err, void *user_data);
using MonoDlFallbackClose = void *(*)(void *handle, void *user_data);
using mono_dl_fallback_register_fn = void *(*)(MonoDlFallbackLoad load, MonoDlFallbackSymbol symbol,
                                               MonoDlFallbackClose close, void *user_data);

static void *mono_fallback_load(const char *name, int flags, char **err, void *user_data)
{
    (void)flags;
    (void)user_data;
    if (!name || g_redirectSystemNative.empty()) {
        return nullptr;
    }
    if (std::strcmp(name, "System.Native") != 0 &&
        std::strcmp(name, "libSystem.Native.so") != 0 &&
        std::strstr(name, "libSystem.Native.so") == nullptr &&
        std::strstr(name, "XdtSysNative") == nullptr) {
        return nullptr;
    }
    dlerror();
    void *h = dlopen_force(g_redirectSystemNative.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!h && err) {
        const char *e = dlerror();
        *err = e ? strdup(e) : strdup("fallback dlopen failed");
    } else if (h) {
        log_format(LogLevel::INFO, TAG, "mono_dl_fallback load {} → {}", name,
                   g_redirectSystemNative);
    }
    return h;
}

static void *mono_fallback_symbol(void *handle, const char *name, char **err, void *user_data)
{
    (void)user_data;
    if (!handle || !name) {
        return nullptr;
    }
    dlerror();
    void *sym = dlsym(handle, name);
    if (!sym && err) {
        const char *e = dlerror();
        *err = e ? strdup(e) : nullptr;
    }
    return sym;
}

static void *mono_fallback_close(void *handle, void *user_data)
{
    (void)user_data;
    if (handle) {
        dlclose(handle);
    }
    return nullptr;
}

static bool register_fallback(void *mono)
{
    if (g_fallbackRegistered.load(std::memory_order_acquire)) {
        return true;
    }
    auto reg = reinterpret_cast<mono_dl_fallback_register_fn>(
            dlsym(mono, "mono_dl_fallback_register"));
    if (!reg) {
        log(LogLevel::WARN, TAG, "mono_dl_fallback_register missing");
        return false;
    }
    reg(mono_fallback_load, mono_fallback_symbol, mono_fallback_close, nullptr);
    g_fallbackRegistered.store(true, std::memory_order_release);
    log(LogLevel::INFO, TAG, "mono_dl_fallback_register installed for System.Native");
    return true;
}

static void *mono_waiter_thread(void *)
{
    for (int i = 0; i < 600; ++i) {
        void *mono = dlopen("libmonosgen-2.0.so", RTLD_NOW | RTLD_NOLOAD);
        if (mono && register_fallback(mono)) {
            return nullptr;
        }
        usleep(50 * 1000);
    }
    log(LogLevel::WARN, TAG, "Mono System.Native waiter: monosgen never loaded");
    return nullptr;
}

void mono_sysnative_prepare(const fs::path &gameLibsPath, const fs::path &codeCache)
{
    fs::path src = gameLibsPath / "libSystem.Native.so";
    if (!fs::exists(src)) {
        log_format(LogLevel::WARN, TAG, "game libSystem.Native.so missing at {}", src.string());
        return;
    }
    fs::path outDir = codeCache / "mono-natives";
    std::error_code ec;
    fs::create_directories(outDir, ec);
    const char *newSoname = "libXdtSysNative.so";
    fs::path dst = outDir / newSoname;
    std::string err;
    if (!copy_elf_with_soname(src, dst, newSoname, &err)) {
        log_format(LogLevel::WARN, TAG, "soname remap System.Native: {}", err);
        return;
    }
    dlerror();
    void *h = dlopen_force(dst.c_str(), RTLD_NOW | RTLD_GLOBAL);
    const char *derr = h ? nullptr : dlerror();
    log_format(h ? LogLevel::INFO : LogLevel::WARN, TAG,
               "Preload remapped {}: {}", newSoname, h ? "ok" : (derr ? derr : "fail"));
    if (h) {
        g_redirectSystemNative = dst.string();
    }
}

extern "C" void bepinex_enable_mono_native_redirect(void)
{
    if (g_redirectSystemNative.empty()) {
        log(LogLevel::WARN, TAG, "System.Native remap missing; redirect not enabled");
        return;
    }
    if (void *mono = dlopen("libmonosgen-2.0.so", RTLD_NOW | RTLD_NOLOAD)) {
        if (register_fallback(mono)) {
            return;
        }
    }
    bool expected = false;
    if (!g_waiterStarted.compare_exchange_strong(expected, true)) {
        return;
    }
    pthread_t th;
    if (pthread_create(&th, nullptr, mono_waiter_thread, nullptr) == 0) {
        pthread_detach(th);
        log(LogLevel::INFO, TAG, "Mono System.Native waiter started (fallback only, no Dobby)");
    } else {
        g_waiterStarted.store(false);
        log(LogLevel::ERROR, TAG, "failed to start Mono System.Native waiter");
    }
}
