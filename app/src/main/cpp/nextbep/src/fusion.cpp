// Copyright (c) 2026 XtraCube
#include <unistd.h>
#include <jni.h>
#include <filesystem>
#include <logger.h>
#include <libmain.h>
#include <fusion_config.h>
#include <hooking/il2cpp.h>
#include <hooking/safehook.h>
#include <hooking/allocator.h>
#include <hooking/libunity.h>
#include <dotnet.h>
#include <external/dobby.h>
#include <utilities/java.h>
#include <spoof_cmdline.h>
#include <themis_guard.h>
#include <utilities/soname.h>
#include <dlfcn.h>
#include <android/dlext.h>
#include <link.h>
#include <elf.h>
#include <sys/mman.h>
#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <atomic>
#include <mutex>
#include <vector>
#include <utilities/library.h>

#define TAG "BepInExNative"

namespace fs = std::filesystem;

// Android already has BoringSSL as soname libcrypto.so / libssl.so (conscrypt).
// Plain dlopen(path) returns that existing handle — Heartopia Mono needs the
// game's OpenSSL (BIO_new_mem_buf etc.). FORCE_LOAD maps a second copy.
static void *dlopen_force(const char *path, int flags)
{
    android_dlextinfo ext;
    std::memset(&ext, 0, sizeof(ext));
    ext.flags = ANDROID_DLEXT_FORCE_LOAD;
    void *h = android_dlopen_ext(path, flags, &ext);
    if (h) {
        return h;
    }
    return dlopen(path, flags);
}

// CoreCLR already owns soname libSystem.Native.so (LibMain). Heartopia Mono
// must not bind that copy (InvalidProgramException on DNS). Remap to a unique
// SONAME and redirect dlopen("libSystem.Native.so") → that path. Do NOT call
// mono_dllmap_insert — this Mono (dotnetruntime) asserts if used pre-init.
static std::string g_redirectSystemNative;
static std::string g_redirectIOCompression;
static std::string g_redirectGlobalization;
// Game Mono P/Invoke natives — SINGLE mapping each (never FORCE_LOAD).
// CustomContextWrapper clears nativeLibraryDir; Android ignores LD_LIBRARY_PATH.
// Bare DllImport sonames need preload + dlopen/mono_dl_fallback redirect.
// FORCE_LOAD of a second copy broke AsyncIO (init vs P/Invoke on different
// mappings) → ConnectToRoom hung after --2.
struct CachedGameNative {
    std::string path;
    std::string fileName; // e.g. libXDAsyncIO.so
    void *handle = nullptr;
};

static std::mutex g_gameNativeMu;
static std::vector<CachedGameNative> g_gameNatives;
static std::string g_gameLibsDir;

// Sonames owned by CoreCLR / remapped elsewhere — never steal via game-libs.
static bool is_coreclr_or_remapped_soname(const char *name)
{
    if (!name) {
        return true;
    }
    return std::strstr(name, "System.Native") != nullptr ||
           std::strstr(name, "System.IO.Compression.Native") != nullptr ||
           std::strstr(name, "System.Globalization.Native") != nullptr ||
           std::strstr(name, "System.Security.Cryptography") != nullptr ||
           std::strcmp(name, "libcrypto.so") == 0 ||
           std::strcmp(name, "libssl.so") == 0 ||
           std::strcmp(name, "libcrypto") == 0 ||
           std::strcmp(name, "libssl") == 0 ||
           std::strcmp(name, "crypto") == 0 ||
           std::strcmp(name, "ssl") == 0;
}

// THEMIS decrypts the already-mapped libil2cpp in place, then Mono may bare-dlopen
// "libil2cpp.so". Caching that through game-libs re-maps / re-inits the SO and Unity
// dies with "[il2cpp] can not get corlibImage" + SIGSEGV right after
// "[THEMIS] libil2cpp.so Ready". Never steal il2cpp/unity — leave the live mapping alone.
static bool is_il2cpp_or_unity_soname(const char *name)
{
    if (!name) {
        return false;
    }
    return std::strstr(name, "libil2cpp") != nullptr ||
           std::strstr(name, "il2cpp") != nullptr ||
           std::strstr(name, "libunity") != nullptr ||
           std::strcmp(name, "unity") == 0;
}

// Match Mono DllImport name to libFoo.so: Foo / libFoo / libFoo.so / Foo.so.
static bool dll_name_matches_file(const char *dllName, const char *fileName)
{
    if (!dllName || !fileName || dllName[0] == '\0' || fileName[0] == '\0') {
        return false;
    }
    if (std::strcmp(dllName, fileName) == 0) {
        return true;
    }
    // Strip trailing .so from both sides for comparison.
    auto strip_so = [](const char *s, char *buf, size_t n) {
        std::strncpy(buf, s, n - 1);
        buf[n - 1] = '\0';
        size_t len = std::strlen(buf);
        if (len > 3 && std::strcmp(buf + len - 3, ".so") == 0) {
            buf[len - 3] = '\0';
        }
    };
    char a[256];
    char b[256];
    strip_so(dllName, a, sizeof(a));
    strip_so(fileName, b, sizeof(b));
    if (std::strcmp(a, b) == 0) {
        return true;
    }
    // libFoo vs Foo
    if (std::strncmp(b, "lib", 3) == 0 && std::strcmp(a, b + 3) == 0) {
        return true;
    }
    if (std::strncmp(a, "lib", 3) == 0 && std::strcmp(a + 3, b) == 0) {
        return true;
    }
    return false;
}

static fs::path resolve_game_lib_path(const char *dllName)
{
    if (g_gameLibsDir.empty() || !dllName || dllName[0] == '\0' || dllName[0] == '/') {
        return {};
    }
    fs::path dir(g_gameLibsDir);
    // Try exact, then lib-prefix / .so variants.
    const char *candidates[6];
    char buf0[256];
    char buf1[256];
    char buf2[256];
    size_t n = 0;
    candidates[n++] = dllName;
    if (std::strstr(dllName, ".so") == nullptr) {
        std::snprintf(buf0, sizeof(buf0), "%s.so", dllName);
        candidates[n++] = buf0;
    }
    if (std::strncmp(dllName, "lib", 3) != 0) {
        std::snprintf(buf1, sizeof(buf1), "lib%s", dllName);
        candidates[n++] = buf1;
        if (std::strstr(dllName, ".so") == nullptr) {
            std::snprintf(buf2, sizeof(buf2), "lib%s.so", dllName);
            candidates[n++] = buf2;
        }
    }
    for (size_t i = 0; i < n; ++i) {
        fs::path p = dir / candidates[i];
        if (fs::exists(p)) {
            return p;
        }
    }
    return {};
}

static CachedGameNative *find_cached_unlocked(const char *dllName)
{
    for (auto &e : g_gameNatives) {
        if (dll_name_matches_file(dllName, e.fileName.c_str())) {
            return &e;
        }
    }
    return nullptr;
}

// Plain dlopen + cache. Returns nullptr if not a game-libs native.
static void *dlopen_game_native_cached(const char *dllName, int flags)
{
    if (!dllName || is_coreclr_or_remapped_soname(dllName) ||
        is_il2cpp_or_unity_soname(dllName)) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_gameNativeMu);
    if (CachedGameNative *hit = find_cached_unlocked(dllName)) {
        if (hit->handle) {
            return hit->handle;
        }
        dlerror();
        void *h = dlopen(hit->path.c_str(), flags);
        if (h) {
            hit->handle = h;
        }
        return h;
    }
    fs::path so = resolve_game_lib_path(dllName);
    if (so.empty()) {
        return nullptr;
    }
    dlerror();
    void *h = dlopen(so.c_str(), flags);
    if (!h) {
        return nullptr;
    }
    CachedGameNative e;
    e.path = so.string();
    e.fileName = so.filename().string();
    e.handle = h;
    g_gameNatives.push_back(std::move(e));
    log_format(LogLevel::INFO, TAG, "game-native cache {} → {}", dllName,
               g_gameNatives.back().path);
    return h;
}

static bool preload_cached_game_native(const fs::path &so)
{
    if (!fs::exists(so)) {
        return false;
    }
    std::lock_guard<std::mutex> lock(g_gameNativeMu);
    const std::string fileName = so.filename().string();
    for (auto &e : g_gameNatives) {
        if (e.fileName == fileName) {
            if (!e.handle) {
                dlerror();
                e.handle = dlopen(e.path.c_str(), RTLD_NOW | RTLD_GLOBAL);
            }
            return e.handle != nullptr;
        }
    }
    dlerror();
    void *h = dlopen(so.c_str(), RTLD_NOW | RTLD_GLOBAL);
    const char *err = h ? nullptr : dlerror();
    log_format(h ? LogLevel::INFO : LogLevel::WARN, TAG,
               "Preload game-native {}: {}", so.string(),
               h ? "ok" : (err ? err : "fail"));
    if (!h) {
        return false;
    }
    CachedGameNative e;
    e.path = so.string();
    e.fileName = fileName;
    e.handle = h;
    g_gameNatives.push_back(std::move(e));
    return true;
}

using dlopen_fn = void *(*)(const char *, int);
using android_dlopen_ext_fn = void *(*)(const char *, int, const android_dlextinfo *);
static dlopen_fn g_orig_dlopen = nullptr;
static android_dlopen_ext_fn g_orig_android_dlopen_ext = nullptr;

// Set true after Unity/il2cpp are loaded so CoreCLR's early bare soname probes
// still hit its own System.Native. Mono StartMono runs later (HotFix).
static std::atomic<bool> g_redirectEnabled{false};
static thread_local int g_inDlopenHook = 0;
static void install_native_dlopen_hooks();

// CoreCLR and Mono both dlopen bare "libSystem.Native.so" but need DIFFERENT
// copies (CoreCLR: TryGetUInt32OSThreadId; game Mono: GetReadDirRBufferSize).
// Redirect only when the caller is monosgen / game mono — never libcoreclr.
static bool caller_is_game_mono()
{
    void *ras[] = {__builtin_return_address(0), __builtin_return_address(1)};
    for (void *ra : ras) {
        if (!ra) {
            continue;
        }
        Dl_info info;
        std::memset(&info, 0, sizeof(info));
        if (!dladdr(ra, &info) || !info.dli_fname) {
            continue;
        }
        const char *f = info.dli_fname;
        if (std::strstr(f, "libcoreclr") || std::strstr(f, "libclrjit") ||
            std::strstr(f, "libhostpolicy") || std::strstr(f, "libhostfxr") ||
            std::strstr(f, "libnethost") || std::strstr(f, "libSystem.Native.so")) {
            return false;
        }
        if (std::strstr(f, "monosgen") || std::strstr(f, "libmono-") ||
            std::strstr(f, "libmono.") || std::strstr(f, "/mono/")) {
            return true;
        }
    }
    return false;
}

static const char *redirect_native_path(const char *filename, bool forMono)
{
    if (!g_redirectEnabled.load(std::memory_order_acquire) || !forMono) {
        return nullptr;
    }
    if (!filename || filename[0] == '\0') {
        return nullptr;
    }
    // Absolute paths: never steal CoreCLR / APK copies. Mono uses bare sonames.
    if (filename[0] == '/') {
        return nullptr;
    }
    if (!g_redirectSystemNative.empty() &&
        (std::strcmp(filename, "libSystem.Native.so") == 0 ||
         std::strcmp(filename, "System.Native") == 0)) {
        return g_redirectSystemNative.c_str();
    }
    if (!g_redirectIOCompression.empty() &&
        (std::strcmp(filename, "libSystem.IO.Compression.Native.so") == 0 ||
         std::strcmp(filename, "System.IO.Compression.Native") == 0)) {
        return g_redirectIOCompression.c_str();
    }
    if (!g_redirectGlobalization.empty() &&
        (std::strcmp(filename, "libSystem.Globalization.Native.so") == 0 ||
         std::strcmp(filename, "System.Globalization.Native") == 0)) {
        return g_redirectGlobalization.c_str();
    }
    // Game Mono P/Invoke (XDAsyncIO / libzstd / kcpsb / …) is handled by
    // dlopen_game_native_cached in the hooks — do not FORCE_LOAD those.
    return nullptr;
}

static void maybe_guard_themis(const char *filename, void *handle)
{
    if (!handle || !filename) return;
    if (strstr(filename, "libthemis-v8.so") == nullptr &&
        strstr(filename, "themis-v8") == nullptr) {
        return;
    }
    install_themis_f000000a_guard();
}

// Game ClassLoader ns often has permitted_paths=/data:/mnt/expand only. Adreno's
// eglSubDriverAndroid.so lives under /vendor/lib64/egl and must load via sphal
// (or a vendor-permitted ns). Route ONLY those vendor blobs through sphal —
// never the public sonames libEGL/libGLESv*/libvulkan. Loading those via sphal
// creates a second EGL stack beside HWUI's system copy; Unity then sees
// eglInitialize ok/live while eglChooseConfig returns EGL_NOT_INITIALIZED
// (Samsung API 37). Older tablets keep working: public sonames still resolve
// through the ClassLoader sphal *shared link* (Java ensureGraphicsLinkerNamespace).
static bool is_vendor_graphics_lib(const char *filename)
{
    if (!filename || filename[0] == '\0') {
        return false;
    }
    if (std::strstr(filename, "eglSubDriver") != nullptr ||
        std::strstr(filename, "libEGL_adreno") != nullptr ||
        std::strstr(filename, "libGLESv2_adreno") != nullptr ||
        std::strstr(filename, "libGLESv1_CM_adreno") != nullptr ||
        std::strstr(filename, "vulkan.adreno") != nullptr ||
        std::strstr(filename, "libq3dtools") != nullptr) {
        return true;
    }
    if (filename[0] == '/' && std::strstr(filename, "/vendor/") != nullptr &&
        (std::strstr(filename, "/egl/") != nullptr ||
         std::strstr(filename, "adreno") != nullptr ||
         std::strstr(filename, "/hw/vulkan") != nullptr)) {
        return true;
    }
    return false;
}

static void *dlopen_vendor_graphics(const char *filename, int flags)
{
    using sphal_fn = void *(*)(const char *, int);
    using get_ns_fn = android_namespace_t *(*)(const char *);
    static sphal_fn sphal = nullptr;
    static get_ns_fn get_ns = nullptr;
    static bool resolved = false;
    if (!resolved) {
        resolved = true;
        sphal = reinterpret_cast<sphal_fn>(dlsym(RTLD_DEFAULT, "android_load_sphal_library"));
        if (!sphal) {
            if (void *vndk = dlopen("libvndksupport.so", RTLD_NOW)) {
                sphal = reinterpret_cast<sphal_fn>(
                        dlsym(vndk, "android_load_sphal_library"));
            }
        }
        get_ns = reinterpret_cast<get_ns_fn>(
                dlsym(RTLD_DEFAULT, "android_get_exported_namespace"));
    }
    if (sphal) {
        dlerror();
        if (void *h = sphal(filename, flags)) {
            log_format(LogLevel::INFO, TAG, "sphal graphics load {}", filename);
            return h;
        }
    }
    if (get_ns) {
        android_namespace_t *ns = get_ns("sphal");
        if (!ns) {
            ns = get_ns("vendor");
        }
        if (ns) {
            android_dlextinfo ext;
            std::memset(&ext, 0, sizeof(ext));
            ext.flags = ANDROID_DLEXT_USE_NAMESPACE;
            ext.library_namespace = ns;
            dlerror();
            void *h = g_orig_android_dlopen_ext
                              ? g_orig_android_dlopen_ext(filename, flags, &ext)
                              : android_dlopen_ext(filename, flags, &ext);
            if (h) {
                log_format(LogLevel::INFO, TAG, "namespace graphics load {}", filename);
                return h;
            }
        }
    }
    return nullptr;
}

static void *hooked_dlopen(const char *filename, int flags)
{
    if (g_inDlopenHook) {
        return g_orig_dlopen ? g_orig_dlopen(filename, flags) : nullptr;
    }
    ++g_inDlopenHook;
    if (is_vendor_graphics_lib(filename)) {
        if (void *gfx = dlopen_vendor_graphics(filename, flags)) {
            --g_inDlopenHook;
            return gfx;
        }
    }
    const bool forMono = caller_is_game_mono();
    const char *redir = redirect_native_path(filename, forMono);
    void *result = nullptr;
    if (forMono && filename) {
        // Prefer cached game-libs mapping (single copy, no FORCE_LOAD).
        result = dlopen_game_native_cached(filename, flags | RTLD_NOW | RTLD_GLOBAL);
        if (result) {
            log_format(LogLevel::INFO, TAG, "dlopen redirect {} → cached game-native", filename);
            maybe_guard_themis(redir ? redir : filename, result);
            --g_inDlopenHook;
            return result;
        }
    }
    if (redir) {
        log_format(LogLevel::INFO, TAG, "dlopen redirect {} → {}", filename, redir);
        // System.Native remaps need FORCE_LOAD semantics vs CoreCLR soname.
        result = dlopen_force(redir, flags);
    } else {
        result = g_orig_dlopen ? g_orig_dlopen(filename, flags) : dlopen(filename, flags);
    }
    maybe_guard_themis(redir ? redir : filename, result);
    --g_inDlopenHook;
    return result;
}

static void *hooked_android_dlopen_ext(const char *filename, int flags,
                                       const android_dlextinfo *info)
{
    if (g_inDlopenHook) {
        return g_orig_android_dlopen_ext
                       ? g_orig_android_dlopen_ext(filename, flags, info)
                       : nullptr;
    }
    ++g_inDlopenHook;
    // Absolute vendor GL loads often arrive via android_dlopen_ext; prefer sphal
    // unless the caller already picked an explicit namespace.
    const bool callerChoseNs =
            info != nullptr && (info->flags & ANDROID_DLEXT_USE_NAMESPACE) != 0;
    if (!callerChoseNs && is_vendor_graphics_lib(filename)) {
        if (void *gfx = dlopen_vendor_graphics(filename, flags)) {
            --g_inDlopenHook;
            return gfx;
        }
    }
    const bool forMono = caller_is_game_mono();
    const char *redir = redirect_native_path(filename, forMono);
    void *result = nullptr;
    if (forMono && filename) {
        result = dlopen_game_native_cached(filename, flags | RTLD_NOW | RTLD_GLOBAL);
        if (result) {
            log_format(LogLevel::INFO, TAG,
                       "android_dlopen_ext redirect {} → cached game-native", filename);
            --g_inDlopenHook;
            return result;
        }
    }
    if (redir) {
        log_format(LogLevel::INFO, TAG, "android_dlopen_ext redirect {} → {}", filename, redir);
        result = dlopen_force(redir, flags);
    } else {
        result = g_orig_android_dlopen_ext
                         ? g_orig_android_dlopen_ext(filename, flags, info)
                         : android_dlopen_ext(filename, flags, info);
    }
    --g_inDlopenHook;
    return result;
}

extern "C" void bepinex_enable_mono_native_redirect(void)
{
    // Called from LibMain after CoreCLR + il2cpp are loaded. Bare-soname
    // dlopen hooks are safe now: CoreCLR preloads System.Native by absolute
    // APK path (hook skips those); Mono HotFix uses bare "System.Native" /
    // "libSystem.Native.so" and must hit the remapped game copy (has
    // SystemNative_GetReadDirRBufferSize — CoreCLR's copy does not).
    // mono_dl_fallback alone is not enough: the linker already resolved the
    // CoreCLR soname, so Mono's default loader never reaches the fallback.
    install_native_dlopen_hooks();
    g_redirectEnabled.store(true, std::memory_order_release);
    log_format(LogLevel::INFO, TAG,
               "Mono System.Native redirect enabled (dlopen hook + fallback)");
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
    if (!name) {
        return nullptr;
    }
    const char *redir = nullptr;
    if (!g_redirectSystemNative.empty() &&
        (std::strcmp(name, "System.Native") == 0 ||
         std::strcmp(name, "libSystem.Native.so") == 0 ||
         std::strstr(name, "libSystem.Native.so") != nullptr)) {
        redir = g_redirectSystemNative.c_str();
    } else if (!g_redirectIOCompression.empty() &&
               (std::strcmp(name, "System.IO.Compression.Native") == 0 ||
                std::strstr(name, "Compression.Native") != nullptr)) {
        redir = g_redirectIOCompression.c_str();
    } else if (!g_redirectGlobalization.empty() &&
               (std::strcmp(name, "System.Globalization.Native") == 0 ||
                std::strstr(name, "Globalization.Native") != nullptr)) {
        redir = g_redirectGlobalization.c_str();
    }
    if (redir) {
        dlerror();
        void *h = dlopen_force(redir, RTLD_NOW | RTLD_GLOBAL);
        if (!h && err) {
            const char *e = dlerror();
            *err = e ? strdup(e) : strdup("fallback dlopen failed");
        } else {
            log_format(LogLevel::INFO, TAG, "mono_dl_fallback load {} → {}", name, redir);
        }
        return h;
    }
    // Generic game-libs cache (XDAsyncIO, libzstd, kcpsb, AkSoundEngine, …).
    void *h = dlopen_game_native_cached(name, RTLD_NOW | RTLD_GLOBAL);
    if (h) {
        log_format(LogLevel::INFO, TAG, "mono_dl_fallback load {} → cached game-native", name);
        return h;
    }
    return nullptr;
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

static void register_mono_dl_fallback_if_possible()
{
    void *mono = dlopen("libmonosgen-2.0.so", RTLD_NOW | RTLD_NOLOAD);
    if (!mono) {
        mono = dlopen_force(
                (fs::path(runtime_config.gameLibraryDirectory) / "libmonosgen-2.0.so").c_str(),
                RTLD_NOW | RTLD_GLOBAL);
    }
    if (!mono) {
        log_format(LogLevel::WARN, TAG, "mono_dl_fallback: monosgen not loaded");
        return;
    }
    auto reg = reinterpret_cast<mono_dl_fallback_register_fn>(
            dlsym(mono, "mono_dl_fallback_register"));
    if (!reg) {
        log_format(LogLevel::WARN, TAG, "mono_dl_fallback_register missing");
        return;
    }
    reg(mono_fallback_load, mono_fallback_symbol, mono_fallback_close, nullptr);
    log_format(LogLevel::INFO, TAG, "mono_dl_fallback_register installed for System.Native");
}

// StubActivity/HWUI may eglInitialize(EGL_DEFAULT_DISPLAY) on the UI thread before
// UnityMain runs. On Samsung Android 16+ Adreno, Unity's later eglInitialize of the
// same display returns false while eglGetError() is EGL_SUCCESS — Unity then aborts
// with EGL_NOT_INITIALIZED. Treat an already-live display as success; only then retry
// via terminate+initialize. No-op when the first init succeeds (tablet / stock path).
using eglInitialize_fn = int (*)(void *dpy, int *major, int *minor);
using eglGetError_fn = int (*)();
using eglQueryString_fn = const char *(*)(void *dpy, int name);
using eglTerminate_fn = int (*)(void *dpy);
using eglGetDisplay_fn = void *(*)(void *display_id);
using eglGetPlatformDisplay_fn = void *(*)(int platform, void *native_display,
                                           const void *attrib_list);
using eglChooseConfig_fn = int (*)(void *dpy, const int *attrib_list, void *configs,
                                   int config_size, int *num_config);
static eglInitialize_fn g_orig_eglInitialize = nullptr;
static eglGetError_fn g_eglGetError = nullptr;
static eglQueryString_fn g_eglQueryString = nullptr;
static eglTerminate_fn g_eglTerminate = nullptr;
static eglGetDisplay_fn g_eglGetDisplay = nullptr;
static eglGetPlatformDisplay_fn g_orig_eglGetPlatformDisplay = nullptr;
static eglChooseConfig_fn g_orig_eglChooseConfig = nullptr;
static constexpr int kEglVersion = 0x3054;
static constexpr int kEglSuccess = 0x3000;
static constexpr int kEglNotInitialized = 0x3001;
static constexpr int kEglPlatformAndroidKhr = 0x3141;
static std::atomic<bool> g_inEglInitHook{false};

static bool egl_display_is_live(void *dpy)
{
    return dpy && g_eglQueryString && g_eglQueryString(dpy, kEglVersion) != nullptr;
}

static int hooked_eglInitialize(void *dpy, int *major, int *minor)
{
    if (!g_orig_eglInitialize) {
        return 0;
    }
    if (g_inEglInitHook.load()) {
        return g_orig_eglInitialize(dpy, major, minor);
    }
    static std::atomic<int> calls{0};
    const int n = calls.fetch_add(1);
    g_inEglInitHook.store(true);
    int ok = g_orig_eglInitialize(dpy, major, minor);
    const bool live = egl_display_is_live(dpy);
    const int err = g_eglGetError ? g_eglGetError() : kEglSuccess;
    if (n < 6) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "eglInitialize#%d dpy=%p ok=%d live=%d err=0x%x", n, dpy, ok,
                            live ? 1 : 0, err);
    }
    // Genuine success: init returned true and VERSION is readable.
    if (ok && live) {
        g_inEglInitHook.store(false);
        return ok;
    }
    // Samsung Adreno quirk: false + EGL_SUCCESS, or true without a usable
    // implementation (chooseConfig would then say NOT_INITIALIZED). Reset and
    // retry once. Tablet first-init success never reaches here.
    if (g_eglTerminate) {
        g_eglTerminate(dpy);
    }
    ok = g_orig_eglInitialize(dpy, major, minor);
    const bool live2 = egl_display_is_live(dpy);
    __android_log_print(ok && live2 ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, TAG,
                        "eglInitialize: terminate+retry ok=%d live=%d (err was 0x%x)", ok,
                        live2 ? 1 : 0, err);
    g_inEglInitHook.store(false);
    return (ok && live2) ? 1 : 0;
}

// Android 16+: Unity may call eglGetPlatformDisplay with a non-Android
// platform or out-of-range native display → EGL_BAD_PARAMETER. Fall back to
// eglGetDisplay(EGL_DEFAULT_DISPLAY) so GLES init still gets a real handle.
static void *hooked_eglGetPlatformDisplay(int platform, void *native_display,
                                          const void *attrib_list)
{
    void *dpy = nullptr;
    if (g_orig_eglGetPlatformDisplay) {
        dpy = g_orig_eglGetPlatformDisplay(platform, native_display, attrib_list);
    }
    if (dpy && dpy != reinterpret_cast<void *>(-1)) {
        return dpy;
    }
    const int err = g_eglGetError ? g_eglGetError() : 0;
    void *fallback =
            g_eglGetDisplay ? g_eglGetDisplay(reinterpret_cast<void *>(1)) : nullptr;
    __android_log_print(ANDROID_LOG_WARN, TAG,
                        "eglGetPlatformDisplay platform=0x%x native=%p err=0x%x -> "
                        "fallback DEFAULT %p",
                        platform, native_display, err, fallback);
    (void)kEglPlatformAndroidKhr;
    return fallback;
}

// Samsung API 37: eglInitialize can report ok/live while eglChooseConfig still
// returns EGL_NOT_INITIALIZED (split EGL stack / Adreno after early Themis).
// Re-init the display and retry once. No-op when chooseConfig already succeeds.
static int hooked_eglChooseConfig(void *dpy, const int *attrib_list, void *configs,
                                  int config_size, int *num_config)
{
    if (!g_orig_eglChooseConfig) {
        return 0;
    }
    int ok = g_orig_eglChooseConfig(dpy, attrib_list, configs, config_size, num_config);
    if (ok) {
        return ok;
    }
    const int err = g_eglGetError ? g_eglGetError() : 0;
    if (err != kEglNotInitialized && egl_display_is_live(dpy)) {
        return ok;
    }
    __android_log_print(ANDROID_LOG_WARN, TAG,
                        "eglChooseConfig fail dpy=%p err=0x%x; reinit+retry", dpy, err);
    if (g_eglTerminate) {
        g_eglTerminate(dpy);
    }
    if (g_orig_eglInitialize) {
        int major = 0;
        int minor = 0;
        g_inEglInitHook.store(true);
        g_orig_eglInitialize(dpy, &major, &minor);
        g_inEglInitHook.store(false);
    }
    ok = g_orig_eglChooseConfig(dpy, attrib_list, configs, config_size, num_config);
    __android_log_print(ok ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, TAG,
                        "eglChooseConfig retry -> %s", ok ? "ok" : "fail");
    return ok;
}

static bool resolve_egl_helpers()
{
    void *lib = dlopen("libEGL.so", RTLD_NOW | RTLD_NOLOAD);
    if (!lib) {
        lib = dlopen("libEGL.so", RTLD_NOW);
    }
    if (!lib) {
        return false;
    }
    if (!g_eglGetError) {
        g_eglGetError = reinterpret_cast<eglGetError_fn>(dlsym(lib, "eglGetError"));
    }
    if (!g_eglQueryString) {
        g_eglQueryString = reinterpret_cast<eglQueryString_fn>(dlsym(lib, "eglQueryString"));
    }
    if (!g_eglTerminate) {
        g_eglTerminate = reinterpret_cast<eglTerminate_fn>(dlsym(lib, "eglTerminate"));
    }
    if (!g_eglGetDisplay) {
        g_eglGetDisplay = reinterpret_cast<eglGetDisplay_fn>(dlsym(lib, "eglGetDisplay"));
    }
    if (!g_orig_eglChooseConfig) {
        g_orig_eglChooseConfig =
                reinterpret_cast<eglChooseConfig_fn>(dlsym(lib, "eglChooseConfig"));
    }
    return dlsym(lib, "eglInitialize") != nullptr;
}

// Rewrite JUMP_SLOT/GLOB_DAT for symbol_name inside a loaded ELF (GOT/PLT).
// Used because shipped libdobby has no DobbyImportTableReplace, and inline
// hooks on XOM system libEGL can be a silent no-op on API 37.
static bool replace_elf_import(const char *lib_basename, const char *symbol_name,
                               void *hook, void **orig_out)
{
    if (!lib_basename || !symbol_name || !hook) {
        return false;
    }
    struct Ctx {
        const char *base;
        const char *sym;
        void *hook;
        void **orig;
        bool ok;
    } ctx{lib_basename, symbol_name, hook, orig_out, false};

    dl_iterate_phdr(
            [](struct dl_phdr_info *info, size_t, void *data) -> int {
                auto *c = static_cast<Ctx *>(data);
                if (!info->dlpi_name || !*info->dlpi_name) {
                    return 0;
                }
                const char *slash = strrchr(info->dlpi_name, '/');
                const char *name = slash ? slash + 1 : info->dlpi_name;
                if (strcmp(name, c->base) != 0) {
                    return 0;
                }
                const ElfW(Dyn) *dyn = nullptr;
                for (int i = 0; i < info->dlpi_phnum; ++i) {
                    if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
                        dyn = reinterpret_cast<const ElfW(Dyn) *>(
                                info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
                        break;
                    }
                }
                if (!dyn) {
                    return 0;
                }
                const char *strtab = nullptr;
                const ElfW(Sym) *symtab = nullptr;
                const ElfW(Rela) *jmprel = nullptr;
                const ElfW(Rela) *rela = nullptr;
                size_t pltrelsz = 0;
                size_t relasz = 0;
                for (const ElfW(Dyn) *d = dyn; d->d_tag != DT_NULL; ++d) {
                    switch (d->d_tag) {
                        case DT_STRTAB:
                            strtab = reinterpret_cast<const char *>(d->d_un.d_ptr);
                            break;
                        case DT_SYMTAB:
                            symtab = reinterpret_cast<const ElfW(Sym) *>(d->d_un.d_ptr);
                            break;
                        case DT_JMPREL:
                            jmprel = reinterpret_cast<const ElfW(Rela) *>(d->d_un.d_ptr);
                            break;
                        case DT_PLTRELSZ:
                            pltrelsz = d->d_un.d_val;
                            break;
                        case DT_RELA:
                            rela = reinterpret_cast<const ElfW(Rela) *>(d->d_un.d_ptr);
                            break;
                        case DT_RELASZ:
                            relasz = d->d_un.d_val;
                            break;
                        default:
                            break;
                    }
                }
                // Absolute vs bias-relative dynamic pointers vary by loader; normalize.
                auto abs_ptr = [&](const void *p) -> const uint8_t * {
                    auto u = reinterpret_cast<uintptr_t>(p);
                    if (u == 0) {
                        return nullptr;
                    }
                    if (u >= info->dlpi_addr && u < info->dlpi_addr + 0x80000000ULL) {
                        return reinterpret_cast<const uint8_t *>(u);
                    }
                    return reinterpret_cast<const uint8_t *>(info->dlpi_addr + u);
                };
                strtab = reinterpret_cast<const char *>(abs_ptr(strtab));
                symtab = reinterpret_cast<const ElfW(Sym) *>(abs_ptr(symtab));
                jmprel = reinterpret_cast<const ElfW(Rela) *>(abs_ptr(jmprel));
                rela = reinterpret_cast<const ElfW(Rela) *>(abs_ptr(rela));
                if (!strtab || !symtab) {
                    return 0;
                }

                auto try_rela = [&](const ElfW(Rela) *rels, size_t bytes) {
                    if (!rels || bytes < sizeof(ElfW(Rela))) {
                        return;
                    }
                    const size_t n = bytes / sizeof(ElfW(Rela));
                    for (size_t i = 0; i < n; ++i) {
                        const auto type = ELF64_R_TYPE(rels[i].r_info);
                        const auto symi = ELF64_R_SYM(rels[i].r_info);
                        if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) {
                            continue;
                        }
                        if (strcmp(strtab + symtab[symi].st_name, c->sym) != 0) {
                            continue;
                        }
                        auto *slot = reinterpret_cast<void **>(
                                info->dlpi_addr + rels[i].r_offset);
                        long page = sysconf(_SC_PAGESIZE);
                        auto page_addr = reinterpret_cast<uintptr_t>(slot) &
                                         ~(static_cast<uintptr_t>(page) - 1);
                        if (mprotect(reinterpret_cast<void *>(page_addr),
                                     static_cast<size_t>(page),
                                     PROT_READ | PROT_WRITE) != 0) {
                            continue;
                        }
                        if (c->orig && !*c->orig) {
                            *c->orig = *slot;
                        }
                        *slot = c->hook;
                        c->ok = true;
                    }
                };
                try_rela(jmprel, pltrelsz);
                try_rela(rela, relasz);
                return c->ok ? 1 : 0;
            },
            &ctx);
    return ctx.ok;
}

// Patch Unity/libmain GOT. Safe to call more than once — libunity is often
// mapped only after execute_fusion_config, after the early bootstrap hook.
// Do NOT DobbyHook system libEGL: on API 37 XOM the trampoline can return a
// false success while the real body failed (seen as ok=1 + "eglInitialize
// failed (EGL_SUCCESS)"). Call the real dlsym pointer from the quirk body.
static void install_egl_initialize_import_hooks()
{
    if (!resolve_egl_helpers()) {
        return;
    }
    void *lib = dlopen("libEGL.so", RTLD_NOW | RTLD_NOLOAD);
    if (!lib) {
        lib = dlopen("libEGL.so", RTLD_NOW);
    }
    if (lib && !g_orig_eglInitialize) {
        g_orig_eglInitialize =
                reinterpret_cast<eglInitialize_fn>(dlsym(lib, "eglInitialize"));
    }
    if (lib && !g_orig_eglGetPlatformDisplay) {
        void *gpd = dlsym(lib, "eglGetPlatformDisplay");
        if (!gpd) {
            gpd = dlsym(lib, "eglGetPlatformDisplayEXT");
        }
        g_orig_eglGetPlatformDisplay =
                reinterpret_cast<eglGetPlatformDisplay_fn>(gpd);
    }
    // Prefer a GOT slot that already resolved into libEGL (same copy Unity
    // bound). Fall back to dlsym. Never keep a PLT resolver as orig — calling
    // it yields fake success while chooseConfig stays NOT_INITIALIZED.
    auto adopt_if_libegl = [](void *sym, void **dest) {
        if (!sym || !dest) {
            return;
        }
        Dl_info info{};
        if (dladdr(sym, &info) && info.dli_fname &&
            strstr(info.dli_fname, "libEGL") != nullptr) {
            *dest = sym;
        }
    };
    static const char *kImporters[] = {"libunity.so", "libmain.so", nullptr};
    for (int i = 0; kImporters[i]; ++i) {
        void *slotInit = nullptr;
        if (replace_elf_import(kImporters[i], "eglInitialize",
                               reinterpret_cast<void *>(hooked_eglInitialize),
                               &slotInit)) {
            adopt_if_libegl(slotInit, reinterpret_cast<void **>(&g_orig_eglInitialize));
            log_format(LogLevel::INFO, TAG,
                       "hooked eglInitialize via GOT ({})", kImporters[i]);
        }
        void *slotChoose = nullptr;
        if (replace_elf_import(kImporters[i], "eglChooseConfig",
                               reinterpret_cast<void *>(hooked_eglChooseConfig),
                               &slotChoose)) {
            adopt_if_libegl(slotChoose, reinterpret_cast<void **>(&g_orig_eglChooseConfig));
            log_format(LogLevel::INFO, TAG,
                       "hooked eglChooseConfig via GOT ({})", kImporters[i]);
        }
        if (g_orig_eglGetPlatformDisplay &&
            replace_elf_import(kImporters[i], "eglGetPlatformDisplay",
                               reinterpret_cast<void *>(hooked_eglGetPlatformDisplay),
                               &ign)) {
            log_format(LogLevel::INFO, TAG,
                       "hooked eglGetPlatformDisplay via GOT ({})", kImporters[i]);
        }
        if (g_orig_eglGetPlatformDisplay &&
            replace_elf_import(kImporters[i], "eglGetPlatformDisplayEXT",
                               reinterpret_cast<void *>(hooked_eglGetPlatformDisplay),
                               &ign)) {
            log_format(LogLevel::INFO, TAG,
                       "hooked eglGetPlatformDisplayEXT via GOT ({})", kImporters[i]);
        }
    }
}

static void install_egl_initialize_hook()
{
    if (!resolve_egl_helpers()) {
        log_format(LogLevel::WARN, TAG, "libEGL.so not loaded for eglInitialize hook");
        return;
    }
    install_egl_initialize_import_hooks();
    if (!g_orig_eglInitialize) {
        log_format(LogLevel::WARN, TAG, "eglInitialize hook not active");
        return;
    }
    log(LogLevel::INFO, TAG, "eglInitialize quirk hook ready (GOT only, no Dobby)");
}

static void install_native_dlopen_hooks()
{
    static std::atomic<bool> installed{false};
    bool expected = false;
    if (!installed.compare_exchange_strong(expected, true)) {
        return;
    }
    void *libdl = dlopen("libdl.so", RTLD_NOW);
    if (!libdl) {
        installed.store(false);
        log_format(LogLevel::WARN, TAG, "libdl.so for hooks: {}", dlerror() ? dlerror() : "fail");
        return;
    }
    void *dlopenSym = dlsym(libdl, "dlopen");
    void *extSym = dlsym(libdl, "android_dlopen_ext");
    if (dlopenSym &&
        DobbyHook(dlopenSym, reinterpret_cast<void *>(hooked_dlopen),
                  reinterpret_cast<void **>(&g_orig_dlopen)) == 0) {
        log_format(LogLevel::INFO, TAG, "hooked dlopen for System.Native redirect");
    } else {
        log_format(LogLevel::WARN, TAG, "DobbyHook dlopen failed");
    }
    if (extSym &&
        DobbyHook(extSym, reinterpret_cast<void *>(hooked_android_dlopen_ext),
                  reinterpret_cast<void **>(&g_orig_android_dlopen_ext)) == 0) {
        log_format(LogLevel::INFO, TAG, "hooked android_dlopen_ext for System.Native redirect");
    }
}

static void remap_mono_native(const fs::path &gameLibs, const fs::path &outDir,
                              const char *fileName, const char *newSoname,
                              std::string *outPath)
{
    fs::path src = gameLibs / fileName;
    if (!fs::exists(src)) {
        return;
    }
    fs::path dst = outDir / newSoname;
    std::string err;
    if (!copy_elf_with_soname(src, dst, newSoname, &err)) {
        log_format(LogLevel::WARN, TAG, "soname remap {}: {}", fileName, err);
        return;
    }
    dlerror();
    void *h = dlopen_force(dst.c_str(), RTLD_NOW | RTLD_GLOBAL);
    log_format(h ? LogLevel::INFO : LogLevel::WARN, TAG,
               "Preload remapped {}: {}", newSoname,
               h ? "ok" : (dlerror() ? dlerror() : "fail"));
    if (h && outPath) {
        *outPath = dst.string();
    }
}

FusionConfig runtime_config;

static bool execute_fusion_config(const FusionConfig &config)
{
    fusion_print_config(config);

    fs::path gameLibsPath(config.gameLibraryDirectory);
    fs::path codeCache(config.codeCacheDirectory);
    fs::path gamePath = fs::path(config.bepInExDirectory).parent_path();

    // Themis reads /proc/self/cmdline — spoof game package before loading il2cpp.
    if (!config.gamePackageId.empty()) {
        install_cmdline_spoof(config.gamePackageId, codeCache.string());
    }
    // Preload Themis so libil2cpp DT_INIT can decrypt exports in-place.
    {
        fs::path themis = gameLibsPath / "libthemis-v8.so";
        if (fs::exists(themis)) {
            void *th = dlopen(themis.c_str(), RTLD_NOW | RTLD_GLOBAL);
            log_format(th ? LogLevel::INFO : LogLevel::WARN, TAG,
                       "Preload {}: {}", themis.string(), th ? "ok" : (dlerror() ? dlerror() : "fail"));
            if (th) {
                install_themis_f000000a_guard();
            }
        }
    }

    // Heartopia Mono P/Invoke (DataCenter → mimalloc) and Mono System.Net DNS
    // use dlopen by soname. CustomContextWrapper clears nativeLibraryDir, so
    // preload the game's own natives with RTLD_GLOBAL and put game-libs first
    // on LD_LIBRARY_PATH (before CoreCLR's conflicting libSystem.Native.so).
    {
        const char *prev = getenv("LD_LIBRARY_PATH");
        std::string ld = gameLibsPath.string();
        if (prev && *prev) {
            ld.push_back(':');
            ld.append(prev);
        }
        setenv("LD_LIBRARY_PATH", ld.c_str(), 1);
        g_gameLibsDir = gameLibsPath.string();

        // Do NOT preload libSystem.Native.so under its real soname — CoreCLR
        // already claimed it. Remap below after monosgen is up.
        static const char *kForceLoadNatives[] = {
            "libmimalloc.so",
            "libcrypto.so",
            "libssl.so",
            "libSystem.Security.Cryptography.Native.Android.so",
            "libmonosgen-2.0.so",
            "libmono-component-marshal-ilgen.so",
            nullptr
        };
        for (const char **p = kForceLoadNatives; *p; ++p) {
            fs::path so = gameLibsPath / *p;
            if (!fs::exists(so)) continue;
            dlerror();
            void *h = dlopen_force(so.c_str(), RTLD_NOW | RTLD_GLOBAL);
            const char *err = h ? nullptr : dlerror();
            log_format(h ? LogLevel::INFO : LogLevel::WARN, TAG,
                       "Preload game native {}: {}", so.string(),
                       h ? "ok" : (err ? err : "fail"));
            if (h && std::strcmp(*p, "libcrypto.so") == 0) {
                void *bio = dlsym(h, "BIO_new_mem_buf");
                log_format(bio ? LogLevel::INFO : LogLevel::ERROR, TAG,
                           "game libcrypto BIO_new_mem_buf: {}",
                           bio ? "ok" : "MISSING");
            }
        }

        // Mono DllImport surface from Heartopia dumps + known game plugins.
        // Plain dlopen + single-handle cache (see dlopen_game_native_cached).
        static const char *kMonoPInvokeNatives[] = {
            "libXDAsyncIO.so",     // DllImport("XDAsyncIO")
            "libzstd.so",          // DllImport("libzstd")
            "libkcpsb.so",         // DllImport("kcpsb")
            "libAkSoundEngine.so", // DllImport("AkSoundEngine")
            nullptr
        };
        for (const char **p = kMonoPInvokeNatives; *p; ++p) {
            fs::path so = gameLibsPath / *p;
            if (!preload_cached_game_native(so)) {
                continue;
            }
            void *h = dlopen_game_native_cached(*p, RTLD_NOW | RTLD_GLOBAL);
            if (!h) {
                continue;
            }
            if (std::strcmp(*p, "libXDAsyncIO.so") == 0) {
                void *init = dlsym(h, "asyncio_asyncio_init");
                log_format(init ? LogLevel::INFO : LogLevel::ERROR, TAG,
                           "libXDAsyncIO asyncio_asyncio_init: {}",
                           init ? "ok" : "MISSING");
            } else if (std::strcmp(*p, "libzstd.so") == 0) {
                void *ver = dlsym(h, "ZSTD_versionNumber");
                log_format(ver ? LogLevel::INFO : LogLevel::ERROR, TAG,
                           "libzstd ZSTD_versionNumber: {}",
                           ver ? "ok" : "MISSING");
            }
        }

        fs::path remapDir = codeCache / "mono-natives";
        remap_mono_native(gameLibsPath, remapDir,
                          "libSystem.Native.so", "libXdtSysNative.so",
                          &g_redirectSystemNative);
        remap_mono_native(gameLibsPath, remapDir,
                          "libSystem.IO.Compression.Native.so", "libXdtIOCompress.so",
                          &g_redirectIOCompression);
        remap_mono_native(gameLibsPath, remapDir,
                          "libSystem.Globalization.Native.so", "libXdtGlobalization.so",
                          &g_redirectGlobalization);
        // Remapped natives are preloaded under unique SONAMEs. A global dlopen
        // hook for bare "libSystem.Native.so" also intercepts CoreCLR and
        // SIGABRTs libcoreclr — do not install it. Mono redirect is enabled
        // later via mono_dl_fallback_register once the runtime is up.
        if (!g_redirectSystemNative.empty()) {
            register_mono_dl_fallback_if_possible();
        }
    }

    fs::path libUnity;
    fs::path patchedLibIl2Cpp;
    if(config.isIl2Cpp2Mono){
        fs::path MonoPath(config.dotnetDirectory);

        fs::path monoLib = MonoPath / "libmonosgen-2.0.so";
        void* handle = dlopen(monoLib.c_str(), RTLD_GLOBAL | RTLD_NOW);
        if (!handle) {
            log_format(LogLevel::ERROR, TAG, "Failed to dlopen libmonosgen-2.0.so: {}", dlerror());
            return false;
        }
        patchedLibIl2Cpp = MonoPath / "libil2cpp.so";
        handle = dlopen(patchedLibIl2Cpp.c_str(), RTLD_GLOBAL | RTLD_NOW);
        if (!handle) {
            log_format(LogLevel::ERROR, TAG, "Failed to dlopen {}: {}", patchedLibIl2Cpp.string(), dlerror());
            return false;
        }
        using SetOverrideDirs = void (*)(const char*, const char*);
        auto set_override_dirs =
                reinterpret_cast<SetOverrideDirs>(
                        dlsym(handle, "il2cpp2mono_set_override_dirs")
                );
        fs::path dllpath = gamePath / "PersistentData/mono";
        fs::path monopath = MonoPath / "mono";
        log_format(LogLevel::INFO, TAG, "setting il2cpp2mono paths: {}, {}", dllpath.c_str(), monopath.c_str());
        set_override_dirs(dllpath.c_str(), monopath.c_str());
    }
    else{
        patchedLibIl2Cpp = codeCache / "libil2cpp.so";
        fs::path libIl2Cpp = gameLibsPath / "libil2cpp.so";
        void *injected = allocate_setup_injected(libIl2Cpp.c_str(), patchedLibIl2Cpp.c_str(), 1024 * 1024);
        if (!injected || get_injected_pool_base() == 0 || !fs::exists(patchedLibIl2Cpp))
        {
            log_format(LogLevel::ERROR, TAG,
                       "Failed to prepare padded libil2cpp from {} → {}",
                       libIl2Cpp.string(), patchedLibIl2Cpp.string());
            return false;
        }
    }
    if (config.useOriginalLibUnity)
    {
        libUnity = gameLibsPath / "libunity.so";
    }
    else
    {
        libUnity = codeCache / "libunity.so";
    }

    std::string libUnityPath = libUnity.string();
    try_hook_libunity(libUnityPath, (gameLibsPath / "libunity.so").string());

    std::string patchedPath = patchedLibIl2Cpp.string();
    libmain_set_override_il2cpp_path(patchedPath.c_str());
    libmain_set_override_unity_path(libUnityPath.c_str());

    log(LogLevel::INFO, TAG, "BepInEx bootstrap finished successfully.");
    return true;
}

int il2cpp_init_hook(char *domain_name)
{
    const char *d_name = domain_name ? domain_name : "Unknown Domain Name";
    log_format(LogLevel::INFO, TAG, "il2cpp_init called with domain: {}", d_name);
    il2cpp_destroy_init_hook();

    // libunity is mapped by now; re-apply GOT patch before Unity touches EGL.
    install_egl_initialize_import_hooks();

    // call the original il2cpp_init function
    int result = il2cpp_init(domain_name);

    if (runtime_config.initialized)
    {
        // setup environment variables
        setenv("BEPINEX_GAME_ASSEMBLY_PATH", libmain_get_override_il2cpp_path(), 1);
        setenv("NEXT_BEPINEX_PATH", runtime_config.bepInExDirectory.c_str(), 1);
        setenv("NEXT_GAME_BINARY", libmain_get_override_il2cpp_path(), 1);
        setenv("NEXT_GAME_DATA_DIR", runtime_config.unityDataDirectory.c_str(), 1);
        setenv("NEXT_APP_DATA_DIR", runtime_config.appDataDirectory.c_str(), 1);
        setenv("NEXT_UNITY_VERSION", runtime_config.unityVersion.c_str(), 1);

        // Heartopia (Themis): Il2CppDumper VAs into the decrypted on-disk dump.
        // Live lib stays on BEPINEX_GAME_ASSEMBLY_PATH; Cpp2IL reads the dump.
        {
            static std::string dumpPathStr;
            dumpPathStr = (fs::path(runtime_config.bepInExDirectory).parent_path() / "libil2cpp_cpp2il.so").string();
            if (fs::exists(dumpPathStr))
            {
                setenv("NEXT_IL2CPP_DUMP_PATH", dumpPathStr.c_str(), 1);
                setenv("NEXT_IL2CPP_CODEREG", "457c870", 1);
                setenv("NEXT_IL2CPP_METAREG", "457e0c8", 1);
                log_format(LogLevel::INFO, TAG, "Cpp2IL dump override {}", dumpPathStr);
            }
        }

        const char *ssl_cert_path = "/apex/com.android.conscrypt/cacerts";
        const char *backup_cert_path = "/system/etc/security/cacerts";
        if (access(ssl_cert_path, R_OK) == 0) {
            setenv("SSL_CERT_DIR", ssl_cert_path, 1);
        } else if (access(backup_cert_path, R_OK) == 0) {
            setenv("SSL_CERT_DIR", backup_cert_path, 1);
        } else {
            log(LogLevel::WARN, TAG, "No readable SSL cert file found; HTTPS requests may fail.");
        }
        const char *ssl_path = getenv("SSL_CERT_DIR");
        const char *safe_ssl_path = ssl_path ? ssl_path : "(null)";
        log_format(LogLevel::INFO, TAG, "Using {} for SSL certificates", safe_ssl_path);

        fs::path bepInExCoreDirectory = fs::path(runtime_config.bepInExDirectory) / "core";

        DotNetConfig dotNetConfig;
        dotNetConfig.runtimeDir = runtime_config.dotnetDirectory;
        dotNetConfig.managedLibsDir = bepInExCoreDirectory.string();
        dotNetConfig.entryPointAssembly = "BepInEx.Unity.IL2CPP";
        dotNetConfig.entryPointType = "BepInEx.Unity.IL2CPP.NextCoreEntrypoint";
        dotNetConfig.entryPointMethod = "Start";
        dotNetConfig.IsMono = runtime_config.isIl2Cpp2Mono;
        // set TMPDIR for MonoMod lib drops
        setenv("TMPDIR", runtime_config.codeCacheDirectory.c_str(), 1);

        // create AuxFolderPluginList
        auto aux = runtime_config.auxiliaryPluginFolders;
        int size = static_cast<int>(aux.size());
        const char** pointerArray = new const char*[size];

        for (int i = 0; i < size; i++) {
            char* strCopy = new char[aux[i].length() + 1];
            std::copy(aux[i].begin(), aux[i].end(), strCopy);
            strCopy[aux[i].length()] = '\0';

            pointerArray[i] = strCopy;
        }

        AuxPluginFolderList list{};
        list.count = size;
        list.folders = pointerArray;

        // change working directory to fusion's scoped data directory
        chdir(runtime_config.appDataDirectory.c_str());
        dotnet_execute_assembly(dotNetConfig, &list);
    }
    else
    {
        log(LogLevel::WARN, TAG, "Native config not initialized. Skipping modloader initialization.");
    }

    log_format(LogLevel::INFO, TAG, "il2cpp_init returned: {}", result);
    return result;
}

extern "C" [[maybe_unused]] bool fusion_bootstrap_from_libmain(JNIEnv *env)
{
    (void) env;

    log(LogLevel::INFO, TAG, "BepInEx bootstrap starting...");
    // Install dlopen hooks before Unity/libEGL touch vendor GL. Mono soname
    // redirect stays gated on g_redirectEnabled (still set later).
    install_native_dlopen_hooks();
    // Preload public EGL/GLES via the default linker (one process-wide stack
    // with HWUI). Vendor Adreno blobs go through sphal only.
    {
        const char *public_preload[] = {
                "libEGL.so",
                "libGLESv1_CM.so",
                "libGLESv2.so",
                "libGLESv3.so",
                "libvulkan.so",
        };
        for (const char *name : public_preload) {
            if (g_orig_dlopen) {
                g_orig_dlopen(name, RTLD_NOW | RTLD_GLOBAL);
            }
        }
        const char *vendor_preload[] = {
                "/vendor/lib64/egl/eglSubDriverAndroid.so",
                "/vendor/lib64/egl/libEGL_adreno.so",
                "/vendor/lib64/egl/libGLESv2_adreno.so",
        };
        for (const char *name : vendor_preload) {
            if (void *h = dlopen_vendor_graphics(name, RTLD_NOW | RTLD_GLOBAL)) {
                (void)h;
            }
        }
    }
    install_egl_initialize_hook();
    jobject javaConfig = get_fusion_config(env);
    FusionConfig config = fusion_parse_config(env, javaConfig);
    if (javaConfig) {
        env->DeleteLocalRef(javaConfig);
    }
    if (!config.initialized) {
        log(LogLevel::ERROR, TAG, "BepInEx bootstrap aborted: runtime config missing.");
        return false;
    }

    log(LogLevel::INFO, TAG, "Executing BepInEx bootstrap from libmain namespace...");
    if (!execute_fusion_config(config)) {
        log(LogLevel::ERROR, TAG, "Failed to execute BepInEx bootstrap.");
        return false;
    }

    runtime_config = config;
    auto il2cpp_path = libmain_get_override_il2cpp_path();
    if (!il2cpp_initialize(il2cpp_path))
    {
        log_format(LogLevel::ERROR, TAG, "Failed to initialize il2cpp with path: {}", il2cpp_path);
        return false;
    }
    if(runtime_config.isIl2Cpp2Mono){
        setLoadingState(false);
        log(LogLevel::WARN, TAG, "BepInEx is currently disabled in il2cpp2mono.");
        return true;
    }
    // Pool placement and library bounds must be trustworthy before any hooking.
    const uintptr_t pool_base = get_injected_pool_base();
    const uintptr_t il2cpp_base = il2cpp_get_library_base();
    if (pool_base == 0 || il2cpp_base == 0 || pool_base <= il2cpp_base)
    {
        log_format(LogLevel::ERROR, TAG,
                   "Invalid pool/library base relationship! pool=0x{:X}, il2cpp=0x{:X}",
                   pool_base, il2cpp_base);
        return false;
    }

    // plain integer subtraction (pool sits directly above the library image)
    const size_t library_size = pool_base - il2cpp_base;
    constexpr size_t kMaxPlausibleLibrarySize = 512ull * 1024 * 1024;
    if (library_size > kMaxPlausibleLibrarySize)
    {
        log_format(LogLevel::ERROR, TAG,
                   "Implausible library size 0x{:X}; refusing to initialize SafeHook",
                   library_size);
        return false;
    }

    if (!safehook_initialize(il2cpp_get_handle(), il2cpp_base, library_size, allocate_injected))
    {
        log(LogLevel::ERROR, TAG, "Failed to initialize SafeHook");
        return false;
    }

    log(LogLevel::INFO, TAG, "Installing il2cpp hooks...");
    il2cpp_install_init_hook(il2cpp_init_hook);
    log(LogLevel::INFO, TAG, "il2cpp hooks installed successfully!");
    log(LogLevel::INFO, TAG, "BepInEx bootstrap finished successfully.");
    return true;
}

