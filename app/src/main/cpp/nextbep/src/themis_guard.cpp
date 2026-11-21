// THEMIS F000000A → exit_group(1) guard.
//
// Kill site logs via __android_log_print("[THEMIS]", "System Error: %X", 0xF000000A)
// then immediately executes mov w0,#1; mov w8,#0x5e; svc #0.
//
// A full /proc/self/maps RX scan takes seconds in Unity and returns too late.
// Only scan mappings whose path contains "themis" (or anonymous siblings in the
// same size band as libthemis-v8.so ~3.8MB), and nop every exit_group(1) triad
// in those mappings — must finish before the hooked log_print returns.

#include <themis_guard.h>
#include <logger.h>
#include <external/dobby.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <pthread.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <cstdint>
#include <vector>

#define TAG "BepInExNative"

namespace {

static const uint8_t kKillSigPrefix[] = {
    0x43, 0x01, 0x80, 0x52,
    0xa1, 0xf2, 0xff, 0xb0,
    0x21, 0xf0, 0x3f, 0x91,
    0x82, 0xf2, 0xff, 0xb0,
    0x42, 0x84, 0x27, 0x91,
    0x60, 0x00, 0x80, 0x52,
    0x03, 0x00, 0xbe, 0x72,
};

static constexpr int kBneBeforeSig = 8;
static constexpr int kExitTriadAfterSig = 0x20;
static constexpr uint32_t kExpectedBne = 0x54000261;
static constexpr uint32_t kPatchB = 0x14000013;
static constexpr uint32_t kNop = 0xD503201F;
static constexpr uint32_t kMovW0_1 = 0x52800020;
static constexpr uint32_t kMovW8_ExitGroup = 0x52800BC8;
static constexpr uint32_t kSvc0 = 0xD4000001;
static constexpr unsigned kF000000A = 0xF000000Au;
static constexpr size_t kThemisSizeMin = 2u * 1024u * 1024u;
static constexpr size_t kThemisSizeMax = 8u * 1024u * 1024u;

std::atomic<bool> g_logHooked{false};
std::atomic<bool> g_watchdogStarted{false};
std::atomic<int> g_blockedExits{0};

using AndroidLogPrintFn = int (*)(int prio, const char *tag, const char *fmt, ...);
AndroidLogPrintFn g_origLogPrint = nullptr;

struct RxMap {
    uintptr_t start;
    uintptr_t end;
    bool namedThemis;
};

bool write_u32(void *addr, uint32_t value) {
    uintptr_t page = reinterpret_cast<uintptr_t>(addr) &
                     ~(static_cast<uintptr_t>(getpagesize()) - 1);
    if (mprotect(reinterpret_cast<void *>(page), getpagesize() * 2,
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        return false;
    }
    *reinterpret_cast<uint32_t *>(addr) = value;
    __builtin___clear_cache(reinterpret_cast<char *>(addr),
                            reinterpret_cast<char *>(addr) + 4);
    return true;
}

// Returns 1 if bytes changed, 0 if already patched, -1 if not a match.
int neuter_exit_triad(uint32_t *ins) {
    if (ins[0] == kNop && ins[1] == kNop && ins[2] == kNop) return 0;
    if (ins[0] != kMovW0_1 || ins[1] != kMovW8_ExitGroup || ins[2] != kSvc0) {
        return -1;
    }
    return (write_u32(ins + 0, kNop) && write_u32(ins + 1, kNop) &&
            write_u32(ins + 2, kNop))
               ? 1
               : -1;
}

int patch_bne(uint32_t *bne) {
    uint32_t cur = *bne;
    if (cur == kPatchB) return 0;
    if (cur != kExpectedBne) {
        uint32_t imm19 = (cur >> 5) & 0x7FFFFu;
        if (((cur & 0xFF000010u) != 0x54000000u) || imm19 != 19u) return -1;
    }
    return write_u32(bne, kPatchB) ? 1 : -1;
}

std::vector<RxMap> list_themis_rx_maps() {
    std::vector<RxMap> out;
    FILE *f = fopen("/proc/self/maps", "re");
    if (!f) return out;
    char line[768];
    while (fgets(line, sizeof(line), f)) {
        uintptr_t start = 0, end = 0;
        char perms[8] = {};
        if (sscanf(line, "%lx-%lx %7s", &start, &end, perms) != 3) continue;
        if (perms[0] != 'r' || perms[2] != 'x') continue;
        size_t sz = end - start;
        if (sz == 0) continue;
        const char *path = nullptr;
        // maps line: START-END PERMS OFFSET DEV INODE PATH
        char *slash = strchr(line, '/');
        char *anon = strstr(line, "[anon");
        if (slash) path = slash;
        bool named = path && strstr(path, "themis") != nullptr;
        bool sizeBand = sz >= kThemisSizeMin && sz <= kThemisSizeMax;
        // Prefer named; also accept size-band anonymous RX (decrypt copies).
        if (!named && !(sizeBand && (anon || !path))) continue;
        out.push_back({start, end, named});
    }
    fclose(f);
    return out;
}

int patch_themis_maps() {
    int sites = 0;
    int triads = 0;
    auto maps = list_themis_rx_maps();
    for (const auto &m : maps) {
        auto *base = reinterpret_cast<uint8_t *>(m.start);
        size_t size = m.end - m.start;

        // 1) Canonical F000000A kill signature.
        for (size_t off = 0; off + sizeof(kKillSigPrefix) + kExitTriadAfterSig + 12 <= size;
             off += 4) {
            if (memcmp(base + off, kKillSigPrefix, sizeof(kKillSigPrefix)) != 0) continue;
            if (off < static_cast<size_t>(kBneBeforeSig)) continue;
            auto *bne = reinterpret_cast<uint32_t *>(base + off - kBneBeforeSig);
            auto *exitIns = reinterpret_cast<uint32_t *>(base + off + kExitTriadAfterSig);
            int bneRc = patch_bne(bne);
            int exitRc = neuter_exit_triad(exitIns);
            if (bneRc > 0 || exitRc > 0) {
                ++sites;
                log_format(LogLevel::INFO, TAG,
                           "themis F000000A site@{:#x} bne={} exit={} named={}",
                           m.start + off, bneRc > 0, exitRc > 0, m.namedThemis);
            }
        }

        // 2) Every exit_group(1) triad in themis-sized mappings (boot kill paths).
        for (size_t off = 0; off + 12 <= size; off += 4) {
            auto *p = reinterpret_cast<uint32_t *>(base + off);
            if (neuter_exit_triad(p) > 0) ++triads;
        }
    }
    if (sites || triads) {
        log_format(LogLevel::INFO, TAG,
                   "themis F000000A patch maps={} sites={} triads={}",
                   maps.size(), sites, triads);
    }
    return sites + triads;
}

int hooked_android_log_print(int prio, const char *tag, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);

    bool isThemisKill = false;
    if (tag && fmt && std::strcmp(tag, "[THEMIS]") == 0 &&
        std::strstr(fmt, "System Error") != nullptr) {
        va_list ap2;
        va_copy(ap2, ap);
        unsigned code = va_arg(ap2, unsigned);
        va_end(ap2);
        if (code == kF000000A) isThemisKill = true;
    }

    // Patch BEFORE printing when we know it's the kill — minimize time before
    // return into the exit_group triad.
    if (isThemisKill) {
        int n = patch_themis_maps();
        int seq = g_blockedExits.fetch_add(1) + 1;
        log_format(LogLevel::WARN, TAG,
                   "themis F000000A exit blocked (#{}) patched={}", seq, n);
        if (!g_watchdogStarted.exchange(true)) {
            pthread_t th;
            pthread_create(
                &th, nullptr,
                [](void *) -> void * {
                    for (int i = 0; i < 120; ++i) {
                        usleep(500 * 1000);
                        patch_themis_maps();
                    }
                    g_watchdogStarted.store(false);
                    return nullptr;
                },
                nullptr);
            pthread_detach(th);
        }
    }

    int rc = 0;
    using VPrintFn = int (*)(int, const char *, const char *, va_list);
    static VPrintFn vprint = nullptr;
    static bool resolved = false;
    if (!resolved) {
        resolved = true;
        vprint = reinterpret_cast<VPrintFn>(dlsym(RTLD_DEFAULT, "__android_log_vprint"));
    }
    if (vprint) {
        rc = vprint(prio, tag, fmt, ap);
    } else if (g_origLogPrint) {
        char buf[512];
        vsnprintf(buf, sizeof(buf), fmt, ap);
        rc = g_origLogPrint(prio, tag, "%s", buf);
    }
    va_end(ap);
    return rc;
}

bool install_log_hook() {
    if (g_logHooked.load(std::memory_order_acquire)) return true;
    void *sym = dlsym(RTLD_DEFAULT, "__android_log_print");
    if (!sym) {
        log(LogLevel::ERROR, TAG, "themis guard: __android_log_print not found");
        return false;
    }
    dobby_disable_near_branch_trampoline();
    int rc = DobbyHook(sym,
                       reinterpret_cast<dobby_dummy_func_t>(hooked_android_log_print),
                       reinterpret_cast<dobby_dummy_func_t *>(&g_origLogPrint));
    if (rc != 0) {
        log_format(LogLevel::ERROR, TAG, "themis guard: DobbyHook log_print failed {}", rc);
        return false;
    }
    g_logHooked.store(true, std::memory_order_release);
    log(LogLevel::INFO, TAG, "themis F000000A guard: __android_log_print hooked");
    return true;
}

} // namespace

bool install_themis_f000000a_guard() {
    bool logOk = install_log_hook();
    int n = patch_themis_maps();
    log_format(LogLevel::INFO, TAG, "themis F000000A early patch hits={}", n);
    return logOk;
}
