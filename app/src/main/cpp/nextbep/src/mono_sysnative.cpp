#include <mono_sysnative.h>

#include <android/dlext.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <pthread.h>
#include <string>
#include <unordered_map>
#include <unistd.h>
#include <vector>

#include <external/dobby.h>
#include <logger.h>
#include <utilities/soname.h>

#define TAG "BepInExNative"

namespace fs = std::filesystem;

// HotFix DNS: promote Interop/Sys GetHostEntryForName P/Invoke stub → raw
// icall → remapped SystemNative_GetHostEntryForName BEFORE compile_special
// builds the broken LibraryImport wrapper (InvalidProgramException calli 7).
//
// Exported mono_compile_method is NOT on the JIT trampoline path — do not Dobby it.
// Install assembly_load_hook only after root domain exists (maps → getRoot).
// Prefer mono_dangerous_add_raw_internal_call so compile_special skips the wrapper.

static std::string g_redirectSystemNative;
static void *g_remappedHandle = nullptr;
static void *g_monoHandle = nullptr;
static fs::path g_gameLibsPath;
static std::mutex g_gameNativeMu;
static std::unordered_map<std::string, void *> g_gameNativeByKey;
static std::atomic<bool> g_waiterStarted{false};
static std::atomic<bool> g_icallInstalled{false};
static std::atomic<bool> g_hookArmed{false};

// Bare DllImport sonames Mono needs after ConnectToRoom--2 (nativeLibraryDir empty).
static const char *kGameNativePreload[] = {
        "libzstd.so",
        "libXDAsyncIO.so",
        "libkcpsb.so",
        "libAkSoundEngine.so",
};

using ghe_fn = int32_t (*)(const uint8_t *, int32_t, void *);
using free_fn = void (*)(void *);
using readdir_buf_fn = int32_t (*)();
using readdir_r_fn = int32_t (*)(void *dir, uint8_t *buffer, int32_t bufferSize, void *outputEntry);
static ghe_fn g_nativeGetHostEntry = nullptr;
static free_fn g_nativeFreeHostEntry = nullptr;
static readdir_buf_fn g_nativeGetReadDirRBufferSize = nullptr;
static readdir_r_fn g_nativeReadDirR = nullptr;

using mono_get_root_domain_fn = void *(*)();
using mono_assembly_get_image_fn = void *(*)(void *assembly);
using mono_image_get_name_fn = const char *(*)(void *image);
using mono_class_from_name_fn = void *(*)(void *image, const char *ns, const char *name);
using mono_class_get_nested_types_fn = void *(*)(void *klass, void **iter);
using mono_class_get_name_fn = const char *(*)(void *klass);
using mono_class_get_methods_fn = void *(*)(void *klass, void **iter);
using mono_method_get_name_fn = const char *(*)(void *method);
using mono_method_get_flags_fn = uint32_t (*)(void *method, uint32_t *iflags);
using mono_add_raw_icall_fn = void (*)(const char *name, const void *method);
using mono_add_internal_call_fn = void (*)(const char *name, const void *method);
using mono_install_assembly_load_hook_fn = void (*)(void (*func)(void *assembly, void *user),
                                                    void *user);
using mono_assembly_foreach_fn = void (*)(void (*func)(void *assembly, void *user), void *user);
using mono_gc_safe_enter_fn = void *(*)(uint32_t *stackdata);
using mono_gc_safe_exit_fn = void (*)(void *cookie, uint32_t *stackdata);

static mono_gc_safe_enter_fn g_gcSafeEnter = nullptr;
static mono_gc_safe_exit_fn g_gcSafeExit = nullptr;
static mono_add_raw_icall_fn g_addRawIcall = nullptr;
static mono_add_internal_call_fn g_addIcall = nullptr;

static constexpr uint16_t kMethodPInvokeImpl = 0x2000;
static constexpr uint16_t kMethodInternalCall = 0x1000;

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

static int32_t icall_GetHostEntryForName(const uint8_t *name, int32_t family, void *entry)
{
    if (!g_nativeGetHostEntry) {
        return 4;
    }
    uint32_t stackdata = 0;
    void *cookie = g_gcSafeEnter ? g_gcSafeEnter(&stackdata) : nullptr;
    if (name) {
        log_format(LogLevel::INFO, TAG, "icall GetHostEntryForName host={} family={}",
                   reinterpret_cast<const char *>(name), family);
    }
    int32_t rc = g_nativeGetHostEntry(name, family, entry);
    log_format(LogLevel::INFO, TAG, "icall GetHostEntryForName rc={}", rc);
    if (g_gcSafeExit) {
        g_gcSafeExit(cookie, &stackdata);
    }
    return rc;
}

static void icall_FreeHostEntry(void *entry)
{
    if (!g_nativeFreeHostEntry || !entry) {
        return;
    }
    uint32_t stackdata = 0;
    void *cookie = g_gcSafeEnter ? g_gcSafeEnter(&stackdata) : nullptr;
    g_nativeFreeHostEntry(entry);
    if (g_gcSafeExit) {
        g_gcSafeExit(cookie, &stackdata);
    }
}

// System.Net.SocketAddress cctor → Interop.Sys.GetSocketAddressSizes.
// Hand-implement sizes so we never call a byref-ish System.Native stub via raw icall.
// Returns PAL Error (0 = SUCCESS).
static int32_t icall_GetSocketAddressSizes(int32_t *ipv4, int32_t *ipv6, int32_t *uds,
                                           int32_t *maxAddr)
{
    if (ipv4) {
        *ipv4 = 16; // sizeof(sockaddr_in)
    }
    if (ipv6) {
        *ipv6 = 28; // sizeof(sockaddr_in6)
    }
    if (uds) {
        *uds = 110; // sockaddr_un path practical max on Android
    }
    if (maxAddr) {
        *maxAddr = 128;
    }
    return 0;
}

static bool resolve_native_dns_exports()
{
    if (!g_remappedHandle) {
        return false;
    }
    if (!g_nativeGetHostEntry) {
        g_nativeGetHostEntry = reinterpret_cast<ghe_fn>(
                dlsym(g_remappedHandle, "SystemNative_GetHostEntryForName"));
    }
    if (!g_nativeFreeHostEntry) {
        g_nativeFreeHostEntry = reinterpret_cast<free_fn>(
                dlsym(g_remappedHandle, "SystemNative_FreeHostEntry"));
    }
    if (!g_nativeGetReadDirRBufferSize) {
        g_nativeGetReadDirRBufferSize = reinterpret_cast<readdir_buf_fn>(
                dlsym(g_remappedHandle, "SystemNative_GetReadDirRBufferSize"));
    }
    if (!g_nativeReadDirR) {
        g_nativeReadDirR = reinterpret_cast<readdir_r_fn>(
                dlsym(g_remappedHandle, "SystemNative_ReadDirR"));
    }
    return g_nativeGetHostEntry && g_nativeFreeHostEntry;
}

static int32_t icall_GetReadDirRBufferSize()
{
    if (!g_nativeGetReadDirRBufferSize) {
        return 0;
    }
    return g_nativeGetReadDirRBufferSize();
}

static int32_t icall_ReadDirR(void *dir, uint8_t *buffer, int32_t bufferSize, void *outputEntry)
{
    if (!g_nativeReadDirR) {
        return -1;
    }
    uint32_t stackdata = 0;
    void *cookie = g_gcSafeEnter ? g_gcSafeEnter(&stackdata) : nullptr;
    int32_t rc = g_nativeReadDirR(dir, buffer, bufferSize, outputEntry);
    if (g_gcSafeExit) {
        g_gcSafeExit(cookie, &stackdata);
    }
    return rc;
}

static bool method_words_match(void *method, uint32_t flags, uint32_t iflags)
{
    auto *raw = reinterpret_cast<uint16_t *>(method);
    return raw[0] == static_cast<uint16_t>(flags) &&
           raw[1] == static_cast<uint16_t>(iflags);
}

static void register_icall(const char *key, const void *fn, bool raw)
{
    // raw = mono_dangerous_add_raw_internal_call — blittable only.
    // byref/out params must use mono_add_internal_call (icall wrapper) or
    // mono aborts: marshal-ilgen-stub.c `!m_type_is_byref(t)`.
    if (raw && g_addRawIcall) {
        g_addRawIcall(key, fn);
        return;
    }
    if (g_addIcall) {
        g_addIcall(key, fn);
        return;
    }
    if (g_addRawIcall) {
        g_addRawIcall(key, fn);
    }
}

// "<Foo>g____PInvoke|15_0" → "Foo"; plain "FreeHostEntry" → "FreeHostEntry"
static bool extract_pinvoke_logical_name(const char *methodName, char *out, size_t outLen)
{
    if (!methodName || !out || outLen < 2) {
        return false;
    }
    if (methodName[0] == '<') {
        const char *end = std::strstr(methodName, ">g__");
        if (!end || end <= methodName + 1) {
            return false;
        }
        size_t n = static_cast<size_t>(end - (methodName + 1));
        if (n + 1 > outLen) {
            return false;
        }
        std::memcpy(out, methodName + 1, n);
        out[n] = '\0';
        return true;
    }
    if (std::strlen(methodName) + 1 > outLen) {
        return false;
    }
    std::strncpy(out, methodName, outLen - 1);
    out[outLen - 1] = '\0';
    return true;
}

// DNS (raw/blittable) + SocketAddress sizes (wrapped — managed outs are byref).
// Connect/Send/AddressFamily setters abort or hang; do not promote them.
static const void *resolve_sysnative_for_method(const char *methodName, bool *outRaw)
{
    char logical[128];
    if (!extract_pinvoke_logical_name(methodName, logical, sizeof(logical))) {
        return nullptr;
    }
    if (std::strcmp(logical, "GetHostEntryForName") == 0) {
        if (outRaw) {
            *outRaw = true;
        }
        return reinterpret_cast<const void *>(icall_GetHostEntryForName);
    }
    if (std::strcmp(logical, "FreeHostEntry") == 0) {
        if (outRaw) {
            *outRaw = true;
        }
        return reinterpret_cast<const void *>(icall_FreeHostEntry);
    }
    if (std::strcmp(logical, "GetSocketAddressSizes") == 0) {
        if (outRaw) {
            *outRaw = false; // out int* → mono_add_internal_call wrapper
        }
        return reinterpret_cast<const void *>(icall_GetSocketAddressSizes);
    }
    // CoreLib GetReadDirRBufferSize — also covered by mono_dl_symbol hook when
    // Mono binds CoreCLR's System.Native; keep as belt-and-suspenders.
    if (std::strcmp(logical, "GetReadDirRBufferSize") == 0) {
        if (outRaw) {
            *outRaw = true; // parameterless int
        }
        return reinterpret_cast<const void *>(icall_GetReadDirRBufferSize);
    }
    return nullptr;
}

static bool promote_pinvoke_method(void *mono, void *method)
{
    auto getName = reinterpret_cast<mono_method_get_name_fn>(dlsym(mono, "mono_method_get_name"));
    auto getFlags =
            reinterpret_cast<mono_method_get_flags_fn>(dlsym(mono, "mono_method_get_flags"));
    if (!getName || !getFlags || (!g_addRawIcall && !g_addIcall)) {
        return false;
    }
    const char *name = getName(method);
    if (!name) {
        return false;
    }
    uint32_t iflags = 0;
    uint32_t flags = getFlags(method, &iflags);
    if ((flags & kMethodPInvokeImpl) == 0) {
        return false;
    }
    bool useRaw = false;
    const void *fn = resolve_sysnative_for_method(name, &useRaw);
    if (!fn) {
        return false;
    }
    if (!method_words_match(method, flags, iflags)) {
        log_format(LogLevel::ERROR, TAG, "MonoMethod layout mismatch for {}; not mutating", name);
        return false;
    }
    auto *raw = reinterpret_cast<uint16_t *>(method);
    raw[0] = static_cast<uint16_t>(flags & ~kMethodPInvokeImpl);
    raw[1] = static_cast<uint16_t>(iflags | kMethodInternalCall);
    std::string key = std::string("Interop/Sys::") + name;
    register_icall(key.c_str(), fn, useRaw);
    log_format(LogLevel::INFO, TAG, "registered {} ({})", key,
               useRaw ? "raw_icall" : "add_internal_call");
    return true;
}

static int promote_interop_sys_in_assembly(void *mono, void *assembly)
{
    auto assemblyGetImage =
            reinterpret_cast<mono_assembly_get_image_fn>(dlsym(mono, "mono_assembly_get_image"));
    auto imageGetName =
            reinterpret_cast<mono_image_get_name_fn>(dlsym(mono, "mono_image_get_name"));
    auto classFromName =
            reinterpret_cast<mono_class_from_name_fn>(dlsym(mono, "mono_class_from_name"));
    auto classGetNested = reinterpret_cast<mono_class_get_nested_types_fn>(
            dlsym(mono, "mono_class_get_nested_types"));
    auto classGetName = reinterpret_cast<mono_class_get_name_fn>(dlsym(mono, "mono_class_get_name"));
    auto classGetMethods =
            reinterpret_cast<mono_class_get_methods_fn>(dlsym(mono, "mono_class_get_methods"));
    if (!assemblyGetImage || !classFromName || !classGetNested || !classGetName || !classGetMethods) {
        return 0;
    }
    void *image = assemblyGetImage(assembly);
    if (!image) {
        return 0;
    }
    void *outer = classFromName(image, "", "Interop");
    if (!outer) {
        return 0;
    }
    const char *img = imageGetName ? imageGetName(image) : nullptr;
    log_format(LogLevel::INFO, TAG, "Interop in {}", img ? img : "?");

    int promoted = 0;
    void *niter = nullptr;
    while (void *nested = classGetNested(outer, &niter)) {
        const char *cn = classGetName(nested);
        if (!cn || std::strcmp(cn, "Sys") != 0) {
            continue;
        }
        void *miter = nullptr;
        while (void *method = classGetMethods(nested, &miter)) {
            if (promote_pinvoke_method(mono, method)) {
                ++promoted;
            }
        }
    }
    return promoted;
}

static bool assembly_is_net_socket_dns(const char *imageName)
{
    // Early CoreLib Interop.Sys promotion hits mono before icall hash init →
    // ghashtable 'hash != NULL'. Promote CoreLib only AFTER a late net assembly
    // load (icall table live). DNS/SocketAddress live in these two.
    if (!imageName) {
        return false;
    }
    return std::strstr(imageName, "System.Net.NameResolution") != nullptr ||
           std::strstr(imageName, "System.Net.Primitives") != nullptr;
}

static int promote_corlib_readdir_sys(void *mono)
{
    // CoreLib is already loaded when the assembly hook arms — pull it via
    // mono_get_corlib and promote only the HotFix integrity readdir pair.
    using get_corlib_fn = void *(*)();
    auto getCorlib = reinterpret_cast<get_corlib_fn>(dlsym(mono, "mono_get_corlib"));
    auto classFromName =
            reinterpret_cast<mono_class_from_name_fn>(dlsym(mono, "mono_class_from_name"));
    auto classGetNested = reinterpret_cast<mono_class_get_nested_types_fn>(
            dlsym(mono, "mono_class_get_nested_types"));
    auto classGetName = reinterpret_cast<mono_class_get_name_fn>(dlsym(mono, "mono_class_get_name"));
    auto classGetMethods =
            reinterpret_cast<mono_class_get_methods_fn>(dlsym(mono, "mono_class_get_methods"));
    if (!getCorlib || !classFromName || !classGetNested || !classGetName || !classGetMethods) {
        return 0;
    }
    void *image = getCorlib();
    if (!image) {
        return 0;
    }
    void *outer = classFromName(image, "", "Interop");
    if (!outer) {
        return 0;
    }
    int promoted = 0;
    void *niter = nullptr;
    while (void *nested = classGetNested(outer, &niter)) {
        const char *cn = classGetName(nested);
        if (!cn || std::strcmp(cn, "Sys") != 0) {
            continue;
        }
        void *miter = nullptr;
        while (void *method = classGetMethods(nested, &miter)) {
            auto getName =
                    reinterpret_cast<mono_method_get_name_fn>(dlsym(mono, "mono_method_get_name"));
            const char *mn = getName ? getName(method) : nullptr;
            char logical[128];
            if (!mn || !extract_pinvoke_logical_name(mn, logical, sizeof(logical))) {
                continue;
            }
            if (std::strcmp(logical, "GetReadDirRBufferSize") != 0) {
                continue;
            }
            if (promote_pinvoke_method(mono, method)) {
                ++promoted;
            }
        }
    }
    return promoted;
}

static void on_assembly_loaded(void *assembly, void *user)
{
    void *mono = user ? user : g_monoHandle;
    if (!mono || !assembly || !resolve_native_dns_exports()) {
        return;
    }
    auto assemblyGetImage =
            reinterpret_cast<mono_assembly_get_image_fn>(dlsym(mono, "mono_assembly_get_image"));
    auto imageGetName =
            reinterpret_cast<mono_image_get_name_fn>(dlsym(mono, "mono_image_get_name"));
    if (!assemblyGetImage || !imageGetName) {
        return;
    }
    void *image = assemblyGetImage(assembly);
    const char *img = image ? imageGetName(image) : nullptr;
    if (!assembly_is_net_socket_dns(img)) {
        return;
    }
    int n = promote_interop_sys_in_assembly(mono, assembly);
    static std::atomic<bool> corlibDone{false};
    if (!corlibDone.load(std::memory_order_acquire)) {
        int c = promote_corlib_readdir_sys(mono);
        if (c > 0) {
            corlibDone.store(true, std::memory_order_release);
            n += c;
            log_format(LogLevel::INFO, TAG, "CoreLib Interop/Sys readdir promoted+={}", c);
        }
    }
    if (n > 0) {
        g_icallInstalled.store(true, std::memory_order_release);
        log_format(LogLevel::INFO, TAG, "Interop/Sys icalls promoted+={} in {}", n,
                   img ? img : "?");
    }
}

static bool monosgen_mapped()
{
    FILE *f = std::fopen("/proc/self/maps", "r");
    if (!f) {
        return false;
    }
    char line[512];
    bool found = false;
    while (std::fgets(line, sizeof(line), f)) {
        if (std::strstr(line, "libmonosgen-2.0.so") != nullptr &&
            (std::strstr(line, "r-xp") != nullptr || std::strstr(line, "r-x") != nullptr)) {
            found = true;
            break;
        }
    }
    std::fclose(f);
    return found;
}

static void *resolve_mono_handle()
{
    if (g_monoHandle) {
        return g_monoHandle;
    }
    void *h = dlopen("libmonosgen-2.0.so", RTLD_NOW | RTLD_NOLOAD);
    if (h) {
        g_monoHandle = h;
    }
    return h;
}

static bool arm_assembly_hook_and_scan(void *mono)
{
    if (!mono) {
        return false;
    }
    bool expected = false;
    if (!g_hookArmed.compare_exchange_strong(expected, true)) {
        return g_icallInstalled.load();
    }

    g_addRawIcall = reinterpret_cast<mono_add_raw_icall_fn>(
            dlsym(mono, "mono_dangerous_add_raw_internal_call"));
    g_addIcall =
            reinterpret_cast<mono_add_internal_call_fn>(dlsym(mono, "mono_add_internal_call"));
    g_gcSafeEnter = reinterpret_cast<mono_gc_safe_enter_fn>(
            dlsym(mono, "mono_threads_enter_gc_safe_region_unbalanced"));
    g_gcSafeExit = reinterpret_cast<mono_gc_safe_exit_fn>(
            dlsym(mono, "mono_threads_exit_gc_safe_region_unbalanced"));
    if (!g_addRawIcall) {
        log(LogLevel::WARN, TAG, "mono_dangerous_add_raw_internal_call missing; using add_internal_call");
    }
    if (!g_addRawIcall && !g_addIcall) {
        log(LogLevel::ERROR, TAG, "no mono_add_internal_call variant");
        g_hookArmed.store(false);
        return false;
    }

    auto installLoad = reinterpret_cast<mono_install_assembly_load_hook_fn>(
            dlsym(mono, "mono_install_assembly_load_hook"));
    auto assemblyForeach =
            reinterpret_cast<mono_assembly_foreach_fn>(dlsym(mono, "mono_assembly_foreach"));
    if (!installLoad) {
        log(LogLevel::ERROR, TAG, "mono_install_assembly_load_hook missing");
        g_hookArmed.store(false);
        return false;
    }
    installLoad(on_assembly_loaded, mono);
    log(LogLevel::INFO, TAG, "mono assembly load hook armed (post root domain)");
    // Do NOT mono_assembly_foreach from the waiter — SEGV on this runtime.
    // Promotion runs only on the Mono loader thread inside the load hook.
    (void)assemblyForeach;
    return g_icallInstalled.load();
}

static void *domain_waiter_thread(void *)
{
    // Phase 1: wait until ClassLoader maps monosgen — do NOT call mono_* yet.
    for (int i = 0; i < 1800 && !monosgen_mapped(); ++i) {
        usleep(50 * 1000);
    }
    if (!monosgen_mapped()) {
        log(LogLevel::WARN, TAG, "DNS waiter: monosgen never mapped");
        return nullptr;
    }
    log(LogLevel::INFO, TAG, "monosgen mapped; waiting for root domain");

    void *mono = nullptr;
    for (int i = 0; i < 200 && !mono; ++i) {
        mono = resolve_mono_handle();
        if (!mono) {
            usleep(50 * 1000);
        }
    }
    if (!mono) {
        log(LogLevel::WARN, TAG, "DNS waiter: monosgen handle not visible via dlopen");
        return nullptr;
    }

    auto getRoot = reinterpret_cast<mono_get_root_domain_fn>(dlsym(mono, "mono_get_root_domain"));
    if (!getRoot) {
        log(LogLevel::ERROR, TAG, "mono_get_root_domain missing");
        return nullptr;
    }

    // Phase 2: poll root domain slowly — only after monosgen is live.
    for (int i = 0; i < 2400; ++i) { // ~120s @ 50ms
        if (getRoot()) {
            log(LogLevel::INFO, TAG, "mono root domain up; arming assembly hook");
            arm_assembly_hook_and_scan(mono);
            return nullptr;
        }
        usleep(50 * 1000);
    }
    log(LogLevel::WARN, TAG, "DNS waiter timed out waiting for root domain");
    return nullptr;
}

using mono_dl_fallback_load_fn = void *(*)(const char *name, int flags, char **err, void *user);
using mono_dl_fallback_symbol_fn = void *(*)(void *handle, const char *name, char **err, void *user);
using mono_dl_fallback_close_fn = void (*)(void *handle, void *user);
using mono_dl_fallback_register_fn = void *(*)(mono_dl_fallback_load_fn load,
                                               mono_dl_fallback_symbol_fn symbol,
                                               mono_dl_fallback_close_fn close, void *user);

static bool name_is_system_native(const char *name)
{
    if (!name) {
        return false;
    }
    return std::strcmp(name, "System.Native") == 0 ||
           std::strcmp(name, "libSystem.Native") == 0 ||
           std::strcmp(name, "libSystem.Native.so") == 0;
}

static bool name_is_reserved_soname(const char *name)
{
    // Never steal CoreCLR / OpenSSL / remapped System.Native via game-native cache.
    if (!name) {
        return true;
    }
    return name_is_system_native(name) ||
           std::strstr(name, "System.Native") != nullptr ||
           std::strstr(name, "crypto") != nullptr ||
           std::strstr(name, "ssl") != nullptr ||
           std::strstr(name, "coreclr") != nullptr ||
           std::strstr(name, "monosgen") != nullptr;
}

static std::string normalize_soname_key(const char *name)
{
    if (!name || !*name) {
        return {};
    }
    std::string s = name;
    if (s.rfind("lib", 0) != 0) {
        s = "lib" + s;
    }
    if (s.size() < 3 || s.compare(s.size() - 3, 3, ".so") != 0) {
        s += ".so";
    }
    return s;
}

static void *cache_game_native_file(const fs::path &file)
{
    if (!fs::exists(file)) {
        return nullptr;
    }
    std::string key = file.filename().string();
    {
        std::lock_guard<std::mutex> lock(g_gameNativeMu);
        auto it = g_gameNativeByKey.find(key);
        if (it != g_gameNativeByKey.end()) {
            return it->second;
        }
    }
    dlerror();
    // Plain dlopen — FORCE_LOAD of a second copy broke AsyncIO (init A, P/Invoke B).
    void *h = dlopen(file.c_str(), RTLD_NOW | RTLD_GLOBAL);
    const char *err = h ? nullptr : dlerror();
    log_format(h ? LogLevel::INFO : LogLevel::WARN, TAG, "Preload game-native {}: {}",
               file.string(), h ? "ok" : (err ? err : "fail"));
    if (!h) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_gameNativeMu);
    g_gameNativeByKey.emplace(key, h);
    return h;
}

static void preload_game_natives(const fs::path &gameLibsPath)
{
    g_gameLibsPath = gameLibsPath;
    for (const char *lib : kGameNativePreload) {
        void *h = cache_game_native_file(gameLibsPath / lib);
        if (!h) {
            continue;
        }
        if (std::strcmp(lib, "libzstd.so") == 0) {
            auto ver = reinterpret_cast<unsigned (*)()>(dlsym(h, "ZSTD_versionNumber"));
            log_format(ver ? LogLevel::INFO : LogLevel::WARN, TAG, "libzstd ZSTD_versionNumber: {}",
                       ver ? "ok" : "MISSING");
        } else if (std::strcmp(lib, "libXDAsyncIO.so") == 0) {
            void *init = dlsym(h, "asyncio_asyncio_init");
            log_format(init ? LogLevel::INFO : LogLevel::WARN, TAG,
                       "libXDAsyncIO asyncio_asyncio_init: {}", init ? "ok" : "MISSING");
        }
    }
}

static void *lookup_game_native(const char *name)
{
    if (!name || name_is_reserved_soname(name) || g_gameLibsPath.empty()) {
        return nullptr;
    }
    std::string key = normalize_soname_key(name);
    if (key.empty()) {
        return nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(g_gameNativeMu);
        auto it = g_gameNativeByKey.find(key);
        if (it != g_gameNativeByKey.end()) {
            log_format(LogLevel::INFO, TAG, "dlopen redirect {} → cached game-native", key);
            return it->second;
        }
    }
    void *h = cache_game_native_file(g_gameLibsPath / key);
    if (h) {
        log_format(LogLevel::INFO, TAG, "dlopen redirect {} → cached game-native", key);
    }
    return h;
}

static void *sysnative_fallback_load(const char *name, int /*flags*/, char **err, void * /*user*/)
{
    if (name_is_system_native(name) && g_remappedHandle) {
        static std::atomic<bool> logged{false};
        if (!logged.exchange(true)) {
            log_format(LogLevel::INFO, TAG, "mono_dl_fallback load {} → libXdtSysNative", name);
        }
        if (err) {
            *err = nullptr;
        }
        return g_remappedHandle;
    }
    void *game = lookup_game_native(name);
    if (game) {
        if (err) {
            *err = nullptr;
        }
        return game;
    }
    return nullptr; // not ours — Mono continues with default dlopen
}

static void *sysnative_fallback_symbol(void *handle, const char *name, char **err, void * /*user*/)
{
    if (!name) {
        return nullptr;
    }
    void *sym = nullptr;
    if (handle) {
        sym = dlsym(handle, name);
    }
    if (!sym && g_remappedHandle && handle != g_remappedHandle) {
        sym = dlsym(g_remappedHandle, name);
    }
    if (!sym) {
        sym = dlsym(RTLD_DEFAULT, name);
    }
    if (err) {
        *err = nullptr;
    }
    return sym;
}

// Public mono_lookup_pinvoke_call is unused by the HotFix path (internal only).
// Disassembled: both public + internal BL lookup_pinvoke_call_impl @ RVA 0x2973e8
// on this libmonosgen-2.0.so. Hook that to satisfy ReadDirR from remapped game lib.
static constexpr uintptr_t kLookupPinvokeCallImplRva = 0x2973e8;
using lookup_pinvoke_impl_fn = void *(*)(void *method, void *status);
static lookup_pinvoke_impl_fn g_origLookupPinvokeImpl = nullptr;
static std::atomic<int> g_pinvokeRemapHits{0};

static const char *sysnative_export_for_method(const char *methodName)
{
    char logical[128];
    if (!extract_pinvoke_logical_name(methodName, logical, sizeof(logical))) {
        return nullptr;
    }
    if (std::strcmp(logical, "ReadDirR") == 0) {
        return "SystemNative_ReadDirR";
    }
    if (std::strcmp(logical, "GetReadDirRBufferSize") == 0) {
        return "SystemNative_GetReadDirRBufferSize";
    }
    if (std::strcmp(logical, "OpenDir") == 0) {
        return "SystemNative_OpenDir";
    }
    if (std::strcmp(logical, "CloseDir") == 0) {
        return "SystemNative_CloseDir";
    }
    return nullptr;
}

static void *hooked_lookup_pinvoke_call_impl(void *method, void *status)
{
    void *addr =
            g_origLookupPinvokeImpl ? g_origLookupPinvokeImpl(method, status) : nullptr;
    if (addr || !method || !g_remappedHandle || !g_monoHandle) {
        return addr;
    }
    auto getName =
            reinterpret_cast<mono_method_get_name_fn>(dlsym(g_monoHandle, "mono_method_get_name"));
    const char *mn = getName ? getName(method) : nullptr;
    const char *exportName = mn ? sysnative_export_for_method(mn) : nullptr;
    if (!exportName) {
        return nullptr;
    }
    void *gameSym = dlsym(g_remappedHandle, exportName);
    if (!gameSym) {
        return nullptr;
    }
    int n = g_pinvokeRemapHits.fetch_add(1) + 1;
    if (n <= 8 || (n % 64) == 0) {
        log_format(LogLevel::INFO, TAG, "pinvoke_impl {} → {} (#{})", mn, exportName, n);
    }
    // Clear MonoLookupPInvokeStatus { err_code, err_arg } so callers treat as OK.
    if (status) {
        std::memset(status, 0, 16);
    }
    return gameSym;
}

static void *monosgen_base_from_handle(void *mono)
{
    Dl_info info;
    std::memset(&info, 0, sizeof(info));
    void *probe = dlsym(mono, "mono_lookup_pinvoke_call");
    if (!probe || !dladdr(probe, &info) || !info.dli_fbase) {
        return nullptr;
    }
    return info.dli_fbase;
}

static void install_lookup_pinvoke_impl_hook()
{
    if (!g_monoHandle || g_origLookupPinvokeImpl) {
        return;
    }
    void *base = monosgen_base_from_handle(g_monoHandle);
    if (!base) {
        log(LogLevel::WARN, TAG, "monosgen base unknown; cannot hook pinvoke_impl");
        return;
    }
    void *sym = reinterpret_cast<void *>(reinterpret_cast<uintptr_t>(base) +
                                         kLookupPinvokeCallImplRva);
    dobby_disable_near_branch_trampoline();
    int rc = DobbyHook(sym, reinterpret_cast<dobby_dummy_func_t>(hooked_lookup_pinvoke_call_impl),
                       reinterpret_cast<dobby_dummy_func_t *>(&g_origLookupPinvokeImpl));
    log_format(rc == 0 ? LogLevel::INFO : LogLevel::ERROR, TAG,
               "Dobby lookup_pinvoke_call_impl @+{:x}: {}", kLookupPinvokeCallImplRva,
               rc == 0 ? "ok" : "fail");
}

static void register_sysnative_dl_fallback()
{
    if (!g_monoHandle || !g_remappedHandle) {
        return;
    }
    install_lookup_pinvoke_impl_hook();
    auto reg = reinterpret_cast<mono_dl_fallback_register_fn>(
            dlsym(g_monoHandle, "mono_dl_fallback_register"));
    if (!reg) {
        log(LogLevel::WARN, TAG, "mono_dl_fallback_register missing");
        return;
    }
    void *cookie = reg(sysnative_fallback_load, sysnative_fallback_symbol, nullptr, nullptr);
    log_format(cookie ? LogLevel::INFO : LogLevel::WARN, TAG,
               "mono_dl_fallback System.Native→XdtSysNative: {}", cookie ? "ok" : "null");
}

static void preload_marshal_ilgen(const fs::path &gameLibsPath)
{
    // marshal-ilgen NEEDs monosgen. Load monosgen first (no mono_* calls), then
    // the component, before Unity's later System.load + mono_jit_init so
    // mono_components_init binds the real marshaller instead of the stub.
    fs::path monosgen = gameLibsPath / "libmonosgen-2.0.so";
    fs::path marshalIlgen = gameLibsPath / "libmono-component-marshal-ilgen.so";
    if (!fs::exists(monosgen)) {
        log_format(LogLevel::WARN, TAG, "early monosgen missing at {}", monosgen.string());
        return;
    }
    if (!fs::exists(marshalIlgen)) {
        log_format(LogLevel::WARN, TAG, "libmono-component-marshal-ilgen.so missing at {}",
                   marshalIlgen.string());
        return;
    }
    // Plain dlopen — NOT FORCE_LOAD. A forced second mapping makes Unity's later
    // System.load bind a different monosgen instance than the one we register
    // mono_dl_fallback / marshal-ilgen against.
    dlerror();
    void *monoH = dlopen(monosgen.c_str(), RTLD_NOW | RTLD_GLOBAL);
    const char *monoErr = monoH ? nullptr : dlerror();
    log_format(monoH ? LogLevel::INFO : LogLevel::WARN, TAG,
               "Early preload libmonosgen-2.0.so: {}",
               monoH ? "ok" : (monoErr ? monoErr : "fail"));
    if (!monoH) {
        return;
    }
    g_monoHandle = monoH;
    dlerror();
    void *mh = dlopen(marshalIlgen.c_str(), RTLD_NOW | RTLD_GLOBAL);
    const char *mderr = mh ? nullptr : dlerror();
    log_format(mh ? LogLevel::INFO : LogLevel::WARN, TAG,
               "Preload libmono-component-marshal-ilgen.so: {}",
               mh ? "ok" : (mderr ? mderr : "fail"));
}

void mono_sysnative_prepare(const fs::path &gameLibsPath, const fs::path &codeCache)
{
    preload_marshal_ilgen(gameLibsPath);
    preload_game_natives(gameLibsPath);

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
    if (!h) {
        return;
    }
    g_redirectSystemNative = dst.string();
    g_remappedHandle = h;
    void *sym = dlsym(h, "SystemNative_GetHostEntryForName");
    void *freeSym = dlsym(h, "SystemNative_FreeHostEntry");
    void *readdirBuf = dlsym(h, "SystemNative_GetReadDirRBufferSize");
    log_format(sym && freeSym ? LogLevel::INFO : LogLevel::ERROR, TAG,
               "remapped GetHostEntry/FreeHostEntry: {}",
               (sym && freeSym) ? "ok" : "MISSING");
    log_format(readdirBuf ? LogLevel::INFO : LogLevel::ERROR, TAG,
               "remapped GetReadDirRBufferSize: {}", readdirBuf ? "ok" : "MISSING");
    // Before mono_jit_init — first Interop.Sys open caches the MonoDl handle.
    register_sysnative_dl_fallback();
}

extern "C" void bepinex_enable_mono_native_redirect(void)
{
    if (g_redirectSystemNative.empty() || !g_remappedHandle) {
        log(LogLevel::WARN, TAG, "System.Native remap missing; DNS icall not armed");
        return;
    }
    bool expected = false;
    if (!g_waiterStarted.compare_exchange_strong(expected, true)) {
        return;
    }
    pthread_t th;
    if (pthread_create(&th, nullptr, domain_waiter_thread, nullptr) == 0) {
        pthread_detach(th);
        log(LogLevel::INFO, TAG, "DNS domain/assembly waiter started");
    } else {
        g_waiterStarted.store(false);
        log(LogLevel::ERROR, TAG, "failed to start DNS waiter");
    }
}
