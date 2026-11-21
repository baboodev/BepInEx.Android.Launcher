// Spoof /proc/self/cmdline so Themis sees the game package under the host process.
#include <spoof_cmdline.h>
#include <logger.h>
#include <external/dobby.h>
#include <fcntl.h>
#include <unistd.h>
#include <dlfcn.h>
#include <cstdarg>
#include <string>
#include <cstring>
#include <cstdio>

#define TAG "BepInExNative"

static std::string g_fake_cmdline_path;
static int (*orig_openat)(int dirfd, const char *pathname, int flags, ...) = nullptr;

static bool is_cmdline_path(const char *pathname) {
    return pathname &&
           (strcmp(pathname, "/proc/self/cmdline") == 0 ||
            strcmp(pathname, "/proc/self/attr/current") == 0 ||
            (strncmp(pathname, "/proc/", 6) == 0 && strstr(pathname, "/cmdline") != nullptr));
}

static int hooked_openat(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    if (is_cmdline_path(pathname) && !g_fake_cmdline_path.empty()) {
        log_format(LogLevel::INFO, TAG, "Spoofing {} -> {}", pathname, g_fake_cmdline_path);
        return orig_openat(AT_FDCWD, g_fake_cmdline_path.c_str(), O_RDONLY, 0);
    }
    if (flags & O_CREAT) {
        return orig_openat(dirfd, pathname, flags, mode);
    }
    return orig_openat(dirfd, pathname, flags, 0);
}

bool install_cmdline_spoof(const std::string &gamePackageId, const std::string &codeCacheDir) {
    if (gamePackageId.empty()) return false;
    g_fake_cmdline_path = codeCacheDir + "/fake_cmdline";
    FILE *f = fopen(g_fake_cmdline_path.c_str(), "wb");
    if (!f) {
        log(LogLevel::ERROR, TAG, "Failed to create fake cmdline file");
        return false;
    }
    // cmdline is NUL-separated argv; Themis typically reads package as argv[0].
    // Do not append -force-vulkan: Unity 2020 still takes the GLES path on this
    // build, and the extra arg only confuses platform-display selection.
    fwrite(gamePackageId.data(), 1, gamePackageId.size(), f);
    fputc('\0', f);
    fclose(f);

    void *sym = dlsym(RTLD_NEXT, "openat");
    if (!sym) sym = dlsym(RTLD_DEFAULT, "openat");
    if (!sym) {
        log(LogLevel::ERROR, TAG, "openat not found for cmdline spoof");
        return false;
    }
    dobby_disable_near_branch_trampoline();
    int rc = DobbyHook(sym,
                       reinterpret_cast<dobby_dummy_func_t>(hooked_openat),
                       reinterpret_cast<dobby_dummy_func_t *>(&orig_openat));
    if (rc != 0) {
        log_format(LogLevel::ERROR, TAG, "Failed to hook openat for cmdline spoof: {}", rc);
        return false;
    }
    log_format(LogLevel::INFO, TAG, "Cmdline spoof installed for package {}", gamePackageId);
    return true;
}
