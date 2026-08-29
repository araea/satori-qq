// mapshide.c — detector-lib GOT filter + in-process seccomp for bare svc.
//
// v5.1 (0.5.7): v5 + __system_property_find (turingxq). Helpers for dlsym /
// getdents / /proc/net/tcp exist but are NOT GOT-patched and tcp is NOT in
// is_proc_exposure_path — 0.5.4 bundled those with a login timeout.
// v5: also patch libturingxq (and still fekit/ckguard); resolve openat dirfd
// so relative "maps" is filtered; cloak /proc/self/status Seccomp_filters;
// GOT covers opendir/popen/stat/dladdr/dlopen/__system_property_get.
// v4: nameless RX renamed as ART jit; so from memfd:jit-cache; seccomp TRAP
// of bare openat. BPF jumps MUST use BPF_JMP|BPF_JEQ|BPF_K.
// See tests/seccomp-filter-test.c. Instant health ≠ anti-kick.
//
// Do NOT hook getSign / getFeKitAttach return values.

#include <stdint.h>
#include <stddef.h>
#include <signal.h>
#include <ucontext.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>

typedef struct {
    const char* dlpi_name;
    uintptr_t dlpi_addr;
    const void* dlpi_phdr;
    uint16_t dlpi_phnum;
} dl_phdr_info_min;

extern int dl_iterate_phdr(int (*cb)(void*, size_t, void*), void* data);
extern int __android_log_print(int prio, const char* tag, const char* fmt, ...);
extern int mprotect(void* addr, size_t len, int prot);
extern long sysconf(int name);
extern char* strstr(const char* h, const char* n);
extern int strcmp(const char* a, const char* b);
extern size_t strlen(const char* s);

typedef struct FILE FILE;
extern FILE* fopen(const char* path, const char* mode);
extern FILE* fdopen(int fd, const char* mode);
extern FILE* popen(const char* cmd, const char* mode);
extern void* opendir(const char* path);
extern int snprintf(char* buf, size_t n, const char* fmt, ...);
extern int stat(const char* path, void* st);
extern int lstat(const char* path, void* st);
extern int statfs(const char* path, void* st);
extern int dladdr(const void* addr, void* info);
extern void* dlopen(const char* filename, int flags);
extern void* dlsym(void* handle, const char* symbol);
extern int __system_property_get(const char* name, char* value);
extern const void* __system_property_find(const char* name);
extern void* readdir(void* dirp);

#define LOGI(...) __android_log_print(4, "Q.Maps", __VA_ARGS__)
#define LOGW(...) __android_log_print(5, "Q.Maps", __VA_ARGS__)

#define SYS_openat        56
#define SYS_read          63
#define SYS_close         57
#define SYS_lseek         62
#define SYS_getdents64    61
#define SYS_memfd_create  279
#define SYS_write         64
#define SYS_prctl         167
#define SYS_seccomp       277
#define SYS_faccessat     48
#define SYS_faccessat2    439
#define SYS_newfstatat    79
#define SYS_readlinkat    78
#define SYS_statx         291
#define SYS_openat2       437
#define AT_FDCWD          (-100)
#define O_RDONLY          0
#define PROT_READ  1
#define PROT_WRITE 2
#define ENOENT 2
#ifndef AUDIT_ARCH_AARCH64
#define AUDIT_ARCH_AARCH64 0xC00000B7u
#endif
#ifndef BPF_JMP
#define BPF_JMP 0x05
#endif
#define PR_SET_VMA 0x53564d41
#define PR_SET_VMA_ANON_NAME 0

typedef struct {
    uint32_t p_type;
    uint32_t p_flags;
    uint64_t p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align;
} Elf64_Phdr;
typedef struct { int64_t d_tag; uint64_t d_val; } Elf64_Dyn;
typedef struct {
    uint32_t st_name;
    uint8_t st_info, st_other;
    uint16_t st_shndx;
    uint64_t st_value, st_size;
} Elf64_Sym;
typedef struct { uint64_t r_offset; uint64_t r_info; int64_t r_addend; } Elf64_Rela;
typedef struct {
    unsigned char e_ident[16];
    uint16_t e_type, e_machine;
    uint32_t e_version;
    uint64_t e_entry, e_phoff, e_shoff;
    uint32_t e_flags;
    uint16_t e_ehsize, e_phentsize, e_phnum, e_shentsize, e_shnum, e_shstrndx;
} Elf64_Ehdr;

#define PT_LOAD 1
#define PT_DYNAMIC 2
#define DT_STRTAB 5
#define DT_SYMTAB 6
#define DT_RELA 7
#define DT_RELASZ 8
#define DT_JMPREL 23
#define DT_PLTRELSZ 2
#define ELF64_R_SYM(i) ((i) >> 32)

static const char* BLOCK[] = {
    "zygisk", "xposed", "lspd", "lsposed", "riru", "magisk",
    "mapshide", "libmapshide", "onebot", "/data/adb", "EdXposed", "substrate",
    "debug_ramdisk", "/system/bin/su", "/system/xbin/su", "kernelsu", "ksud",
    "apatch", "shamiko", "com.topjohnwu", "me.weishu.kernelsu",
    "me.bmax.apatch", "com.noshufou", "eu.chainfire.supersu",
    "zygisk_vector", "libvector", "JingMatrix", "frida", "gadget",
    "linjector", "lsplant", 0
};

static long g_page = 4096;
static uintptr_t g_text_lo;
static uintptr_t g_text_hi;
static int g_seccomp_on;
static int g_named_rx;

static long raw_svc(long n, long a0, long a1, long a2, long a3, long a4, long a5) {
    register long x8 asm("x8") = n;
    register long x0 asm("x0") = a0;
    register long x1 asm("x1") = a1;
    register long x2 asm("x2") = a2;
    register long x3 asm("x3") = a3;
    register long x4 asm("x4") = a4;
    register long x5 asm("x5") = a5;
    asm volatile("svc #0"
            : "+r"(x0)
            : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5)
            : "memory", "cc");
    return x0;
}

static int contains(const char* line, size_t len, const char* b) {
    size_t bl = strlen(b);
    if (bl == 0 || bl > len) return 0;
    for (size_t j = 0; j + bl <= len; j++) {
        size_t k = 0;
        while (k < bl && line[j + k] == b[k]) k++;
        if (k == bl) return 1;
    }
    return 0;
}

static int line_blocked(const char* line, size_t len) {
    for (int i = 0; BLOCK[i]; i++) {
        if (contains(line, len, BLOCK[i])) return 1;
    }
    if (contains(line, len, " r-xp ") || contains(line, len, " r-xp\t")) {
        int has_path = 0;
        for (size_t i = 0; i < len; i++) {
            if (line[i] == '/' || line[i] == '[') { has_path = 1; break; }
        }
        if (!has_path) return 1;
    }
    return 0;
}

static int ends_with(const char* p, const char* suf) {
    if (!p || !suf) return 0;
    size_t lp = strlen(p), ls = strlen(suf);
    if (ls == 0 || ls > lp) return 0;
    return strcmp(p + lp - ls, suf) == 0;
}

static int starts_with(const char* p, size_t n, const char* pre) {
    if (!p || !pre) return 0;
    size_t lp = strlen(pre);
    if (lp > n) return 0;
    for (size_t i = 0; i < lp; i++) if (p[i] != pre[i]) return 0;
    return 1;
}

static int path_denied(const char* p);
static int my_getdents64(int fd, void* dirp, unsigned count);

static int is_proc_exposure_path(const char* p) {
    if (!p) return 0;
    if (!strstr(p, "/proc")) return 0;
    return ends_with(p, "/maps") || ends_with(p, "/smaps") || ends_with(p, "/smaps_rollup")
            || ends_with(p, "/mountinfo") || ends_with(p, "/mounts")
            || ends_with(p, "/status");
}

static int is_net_stat_path(const char* p) {
    if (!p || !strstr(p, "/proc")) return 0;
    return ends_with(p, "/tcp") || ends_with(p, "/tcp6")
            || ends_with(p, "/udp") || ends_with(p, "/udp6");
}

/* Drop OneBot's 127.0.0.1:3001 listener (hex 0BB9) from /proc/net/tcp{,6}. */
static int net_local_port_hidden(const char* line, size_t len) {
    int colons = 0;
    for (size_t j = 0; j + 4 < len; j++) {
        if (line[j] != ':') continue;
        colons++;
        if (colons != 2) continue;
        char a = line[j + 1], b = line[j + 2], c = line[j + 3], d = line[j + 4];
        if (a == '0' && (b == 'B' || b == 'b') && (c == 'B' || c == 'b') && d == '9')
            return 1;
        return 0;
    }
    return 0;
}

static int dent_name_blocked(const char* name) {
    if (!name || !*name || name[0] == '.') return 0;
    if (strcmp(name, "su") == 0 || strcmp(name, "ksud") == 0
            || strcmp(name, "magisk") == 0 || strcmp(name, "apatch") == 0)
        return 1;
    return path_denied(name);
}

static int is_status_path(const char* p) {
    return p && ends_with(p, "/status") && strstr(p, "/proc");
}

static int path_denied(const char* p) {
    if (!p) return 0;
    size_t n = strlen(p);
    for (int i = 0; BLOCK[i]; i++) {
        if (contains(p, n, BLOCK[i])) return 1;
    }
    return 0;
}

static int cmd_denied(const char* p) {
    if (!p) return 0;
    size_t n = strlen(p);
    if (path_denied(p)) return 1;
    return contains(p, n, " magisk") || contains(p, n, "grep magisk")
            || (contains(p, n, "getprop") && contains(p, n, "magisk"));
}

static int prop_denied(const char* p) {
    if (!p) return 0;
    size_t n = strlen(p);
    return contains(p, n, "magisk") || contains(p, n, "zygisk")
            || contains(p, n, "lsposed") || contains(p, n, "lspd")
            || contains(p, n, "riru") || contains(p, n, "kernelsu")
            || contains(p, n, "ksud") || contains(p, n, "apatch")
            || contains(p, n, "shamiko");
}

static int is_detector_path(const char* p) {
    if (!p) return 0;
    return strstr(p, "fekit") || strstr(p, "ckguard") || strstr(p, "wtecdh")
            || strstr(p, "turingxq") || strstr(p, "turing")
            || strstr(p, "libqsec") || strstr(p, "dandelion");
}

/* openat(dirfd, "maps") bypasses a path-only /proc filter. Resolve dirfd. */
static int resolve_at_path(int dirfd, const char* path, char* out, unsigned outsz) {
    if (!path || !out || outsz < 4) return 0;
    if (path[0] == '/') {
        unsigned i = 0;
        while (path[i] && i + 1 < outsz) { out[i] = path[i]; i++; }
        out[i] = 0;
        return 1;
    }
    char link[64];
    char base[512];
    if (dirfd == AT_FDCWD) {
        const char* cwd = "/proc/self/cwd";
        unsigned i = 0;
        while (cwd[i] && i + 1 < sizeof(link)) { link[i] = cwd[i]; i++; }
        link[i] = 0;
    } else {
        if (snprintf(link, sizeof(link), "/proc/self/fd/%d", dirfd) <= 0) return 0;
    }
    long n = raw_svc(SYS_readlinkat, (long)AT_FDCWD, (long)link, (long)base,
            (long)(sizeof(base) - 1), 0, 0);
    if (n <= 0) return 0;
    unsigned bi = (unsigned)n;
    if (bi >= sizeof(base)) bi = sizeof(base) - 1;
    base[bi] = 0;
    unsigned oi = 0;
    while (oi < bi && oi + 1 < outsz) { out[oi] = base[oi]; oi++; }
    if (oi + 1 < outsz && (oi == 0 || out[oi - 1] != '/')) out[oi++] = '/';
    unsigned pi = 0;
    while (path[pi] && oi + 1 < outsz) { out[oi++] = path[pi++]; }
    out[oi] = 0;
    return 1;
}

static const char* effective_path(int dirfd, const char* path, char* buf, unsigned bufsz) {
    if (!path) return path;
    if (path[0] == '/') return path;
    if (resolve_at_path(dirfd, path, buf, bufsz)) return buf;
    return path;
}

static int is_mapping_header(const char* line, size_t len) {
    if (!line || len < 4) return 0;
    size_t i = 0;
    int before = 0, after = 0;
    while (i < len && ((line[i] >= '0' && line[i] <= '9') ||
            (line[i] >= 'a' && line[i] <= 'f') || (line[i] >= 'A' && line[i] <= 'F'))) {
        before = 1;
        i++;
    }
    if (!before || i >= len || line[i++] != '-') return 0;
    while (i < len && ((line[i] >= '0' && line[i] <= '9') ||
            (line[i] >= 'a' && line[i] <= 'f') || (line[i] >= 'A' && line[i] <= 'F'))) {
        after = 1;
        i++;
    }
    return after && i < len && (line[i] == ' ' || line[i] == '\t');
}

static int filtered_proc_fd(const char* path) {
    long fd = raw_svc(SYS_openat, (long)AT_FDCWD, (long)path, (long)O_RDONLY, 0L, 0L, 0L);
    if (fd < 0) return -1;
    long mfd = raw_svc(SYS_memfd_create, (long)"m", 0L, 0L, 0L, 0L, 0L);
    if (mfd < 0) { raw_svc(SYS_close, fd, 0, 0, 0, 0, 0); return -1; }
    char buf[8192];
    char line[1024];
    size_t ll = 0;
    int drop_stanza = 0;
    long r;
    while ((r = raw_svc(SYS_read, fd, (long)buf, (long)sizeof(buf), 0, 0, 0)) > 0) {
        for (long i = 0; i < r; i++) {
            char c = buf[i];
            if (ll < sizeof(line) - 1) line[ll++] = c;
            if (c == '\n') {
                if (is_status_path(path) && starts_with(line, ll, "Seccomp_filters:")) {
                    const char* r = "Seccomp_filters:\t1\n";
                    raw_svc(SYS_write, mfd, (long)r, (long)strlen(r), 0, 0, 0);
                } else if (is_status_path(path) && starts_with(line, ll, "NoNewPrivs:")) {
                    const char* r = "NoNewPrivs:\t0\n";
                    raw_svc(SYS_write, mfd, (long)r, (long)strlen(r), 0, 0, 0);
                } else {
                    if (is_net_stat_path(path) && net_local_port_hidden(line, ll)) {
                        ll = 0;
                        continue;
                    }
                    if (is_mapping_header(line, ll)) drop_stanza = line_blocked(line, ll);
                    else if (!strstr(path, "/smaps")) drop_stanza = 0;
                    if (!drop_stanza && !line_blocked(line, ll))
                        raw_svc(SYS_write, mfd, (long)line, (long)ll, 0, 0, 0);
                }
                ll = 0;
            }
        }
    }
    if (ll > 0) {
        if (is_status_path(path) && starts_with(line, ll, "Seccomp_filters:")) {
            const char* r = "Seccomp_filters:\t1\n";
            raw_svc(SYS_write, mfd, (long)r, (long)strlen(r), 0, 0, 0);
        } else if (is_status_path(path) && starts_with(line, ll, "NoNewPrivs:")) {
            const char* r = "NoNewPrivs:\t0\n";
            raw_svc(SYS_write, mfd, (long)r, (long)strlen(r), 0, 0, 0);
        } else {
            if (is_net_stat_path(path) && net_local_port_hidden(line, ll)) {
                /* drop trailing partial line */
            } else {
                if (is_mapping_header(line, ll)) drop_stanza = line_blocked(line, ll);
                else if (!strstr(path, "/smaps")) drop_stanza = 0;
                if (!drop_stanza && !line_blocked(line, ll))
                    raw_svc(SYS_write, mfd, (long)line, (long)ll, 0, 0, 0);
            }
        }
    }
    raw_svc(SYS_close, fd, 0, 0, 0, 0, 0);
    raw_svc(SYS_lseek, mfd, 0L, 0L, 0, 0, 0);
    return (int)mfd;
}

static int my_openat(int dirfd, const char* path, int flags, int mode) {
    char resolved[768];
    const char* ep = effective_path(dirfd, path, resolved, sizeof(resolved));
    if (is_proc_exposure_path(ep)) {
        int f = filtered_proc_fd(ep);
        if (f >= 0) return f;
    }
    if (path_denied(ep) || path_denied(path)) return -ENOENT;
    return (int)raw_svc(SYS_openat, (long)dirfd, (long)path, (long)flags, (long)mode, 0, 0);
}

static int my_open(const char* path, int flags, int mode) {
    return my_openat(AT_FDCWD, path, flags, mode);
}

static FILE* my_fopen(const char* path, const char* mode) {
    if (is_proc_exposure_path(path)) {
        int f = filtered_proc_fd(path);
        if (f >= 0) {
            FILE* fp = fdopen(f, mode ? mode : "r");
            if (fp) return fp;
            raw_svc(SYS_close, (long)f, 0, 0, 0, 0, 0);
        }
    }
    if (path_denied(path)) return 0;
    return fopen(path, mode);
}

static long my_syscall(long n, long a0, long a1, long a2, long a3, long a4, long a5) {
    if (n == SYS_openat || n == SYS_openat2) {
        char resolved[768];
        const char* path = effective_path((int)a0, (const char*)a1, resolved, sizeof(resolved));
        if (is_proc_exposure_path(path)) {
            int f = filtered_proc_fd(path);
            if (f >= 0) return f;
        }
        if (path_denied(path) || path_denied((const char*)a1)) return -ENOENT;
    }
    if (n == SYS_faccessat || n == SYS_faccessat2 || n == SYS_newfstatat
            || n == SYS_readlinkat || n == SYS_statx) {
        char resolved[768];
        const char* path = effective_path((int)a0, (const char*)a1, resolved, sizeof(resolved));
        if (path_denied(path) || path_denied((const char*)a1)) return -ENOENT;
        if (is_proc_exposure_path(path) && (n == SYS_faccessat || n == SYS_faccessat2))
            return 0;
    }
    return raw_svc(n, a0, a1, a2, a3, a4, a5);
}

static int my_access(const char* path, int mode) {
    if (path_denied(path)) return -ENOENT;
    if (is_proc_exposure_path(path)) return 0;
    return (int)raw_svc(SYS_faccessat, (long)AT_FDCWD, (long)path, (long)mode, 0, 0, 0);
}

static int my_faccessat(int dirfd, const char* path, int mode, int flags) {
    char resolved[768];
    const char* ep = effective_path(dirfd, path, resolved, sizeof(resolved));
    if (path_denied(ep) || path_denied(path)) return -ENOENT;
    if (is_proc_exposure_path(ep)) return 0;
    return (int)raw_svc(SYS_faccessat, (long)dirfd, (long)path, (long)mode, (long)flags, 0, 0);
}

static long my_readlink(const char* path, char* buf, unsigned long bufsz) {
    if (path_denied(path)) return -ENOENT;
    return raw_svc(SYS_readlinkat, (long)AT_FDCWD, (long)path, (long)buf, (long)bufsz, 0, 0);
}

static int module_name_blocked(const char* name);

static long my_readlinkat(int dirfd, const char* path, char* buf, unsigned long bufsz) {
    char resolved[768];
    const char* ep = effective_path(dirfd, path, resolved, sizeof(resolved));
    if (path_denied(ep) || path_denied(path)) return -ENOENT;
    return raw_svc(SYS_readlinkat, (long)dirfd, (long)path, (long)buf, (long)bufsz, 0, 0);
}

static int my_stat(const char* path, void* st) {
    if (path_denied(path)) return -ENOENT;
    return stat(path, st);
}

static int my_lstat(const char* path, void* st) {
    if (path_denied(path)) return -ENOENT;
    return lstat(path, st);
}

static int my_statfs(const char* path, void* st) {
    if (path_denied(path)) return -ENOENT;
    return statfs(path, st);
}

static void* my_opendir(const char* path) {
    if (path_denied(path)) return 0;
    return opendir(path);
}

static FILE* my_popen(const char* cmd, const char* mode) {
    if (cmd_denied(cmd) || path_denied(cmd)) {
        long mfd = raw_svc(SYS_memfd_create, (long)"m", 0L, 0L, 0L, 0L, 0L);
        if (mfd < 0) return 0;
        FILE* fp = fdopen((int)mfd, mode ? mode : "r");
        if (fp) return fp;
        raw_svc(SYS_close, mfd, 0, 0, 0, 0, 0);
        return 0;
    }
    return popen(cmd, mode);
}

typedef struct {
    const char* dli_fname;
    void* dli_fbase;
    const char* dli_sname;
    void* dli_saddr;
} Dl_info_min;

static int my_dladdr(const void* addr, void* info_v) {
    int r = dladdr(addr, info_v);
    if (r && info_v) {
        Dl_info_min* info = (Dl_info_min*)info_v;
        if (module_name_blocked(info->dli_fname)) {
            info->dli_fname = "[anon:dalvik-jit-code-cache]";
            info->dli_sname = 0;
            info->dli_saddr = 0;
        }
    }
    return r;
}

static void* my_dlopen(const char* filename, int flags) {
    if (path_denied(filename)) return 0;
    return dlopen(filename, flags);
}

static int my_sysprop_get(const char* name, char* value) {
    if (prop_denied(name)) {
        if (value) value[0] = 0;
        return 0;
    }
    return __system_property_get(name, value);
}

static const void* my_sysprop_find(const char* name) {
    if (prop_denied(name)) return 0;
    return __system_property_find(name);
}

typedef struct {
    uint64_t d_ino;
    int64_t d_off;
    unsigned short d_reclen;
    unsigned char d_type;
    char d_name[];
} linux_dirent64_min;

static int my_getdents64(int fd, void* dirp, unsigned count) {
    long n = raw_svc(SYS_getdents64, (long)fd, (long)dirp, (long)count, 0, 0, 0);
    if (n <= 0 || !dirp) return (int)n;
    char* buf = (char*)dirp;
    long in = 0, out = 0;
    while (in < n) {
        linux_dirent64_min* d = (linux_dirent64_min*)(buf + in);
        unsigned short reclen = d->d_reclen;
        if (reclen < 20 || in + reclen > n) break;
        if (!dent_name_blocked(d->d_name)) {
            if (in != out) {
                char* src = buf + in;
                char* dst = buf + out;
                for (unsigned i = 0; i < reclen; i++) dst[i] = src[i];
            }
            out += reclen;
        }
        in += reclen;
    }
    return (int)out;
}

static void* my_readdir(void* dirp) {
    for (;;) {
        void* e = readdir(dirp);
        if (!e) return 0;
        const char* name = ((const char*)e) + 19;
        if (!dent_name_blocked(name)) return e;
    }
}

typedef int (*dl_iter_cb_t)(void*, size_t, void*);
typedef struct {
    dl_iter_cb_t callback;
    void* data;
} iter_filter_t;

static int module_name_blocked(const char* name) {
    if (!name || !*name) return 0;
    size_t len = strlen(name);
    for (int i = 0; BLOCK[i]; i++) {
        if (contains(name, len, BLOCK[i])) return 1;
    }
    return 0;
}

static int filtered_iter_cb(void* info_v, size_t size, void* data_v) {
    dl_phdr_info_min* info = (dl_phdr_info_min*)info_v;
    iter_filter_t* filter = (iter_filter_t*)data_v;
    if (module_name_blocked(info && info->dlpi_name ? info->dlpi_name : "")) return 0;
    return filter->callback(info_v, size, filter->data);
}

static int my_dl_iterate_phdr(dl_iter_cb_t callback, void* data) {
    if (!callback) return 0;
    iter_filter_t filter;
    filter.callback = callback;
    filter.data = data;
    return dl_iterate_phdr(filtered_iter_cb, &filter);
}

static void* my_dlsym(void* handle, const char* symbol) {
    if (!symbol) return dlsym(handle, symbol);
    if (strcmp(symbol, "open") == 0 || strcmp(symbol, "open64") == 0) return (void*)my_open;
    if (strcmp(symbol, "openat") == 0 || strcmp(symbol, "openat64") == 0) return (void*)my_openat;
    if (strcmp(symbol, "fopen") == 0 || strcmp(symbol, "fopen64") == 0) return (void*)my_fopen;
    if (strcmp(symbol, "syscall") == 0) return (void*)my_syscall;
    if (strcmp(symbol, "dl_iterate_phdr") == 0) return (void*)my_dl_iterate_phdr;
    if (strcmp(symbol, "access") == 0) return (void*)my_access;
    if (strcmp(symbol, "faccessat") == 0) return (void*)my_faccessat;
    if (strcmp(symbol, "readlink") == 0) return (void*)my_readlink;
    if (strcmp(symbol, "readlinkat") == 0) return (void*)my_readlinkat;
    if (strcmp(symbol, "stat") == 0) return (void*)my_stat;
    if (strcmp(symbol, "lstat") == 0) return (void*)my_lstat;
    if (strcmp(symbol, "statfs") == 0) return (void*)my_statfs;
    if (strcmp(symbol, "opendir") == 0) return (void*)my_opendir;
    if (strcmp(symbol, "popen") == 0) return (void*)my_popen;
    if (strcmp(symbol, "dladdr") == 0) return (void*)my_dladdr;
    if (strcmp(symbol, "dlopen") == 0) return (void*)my_dlopen;
    if (strcmp(symbol, "dlsym") == 0) return (void*)my_dlsym;
    if (strcmp(symbol, "__system_property_get") == 0) return (void*)my_sysprop_get;
    if (strcmp(symbol, "__system_property_find") == 0) return (void*)my_sysprop_find;
    if (strcmp(symbol, "getdents64") == 0) return (void*)my_getdents64;
    if (strcmp(symbol, "readdir") == 0 || strcmp(symbol, "readdir64") == 0) return (void*)my_readdir;
    return dlsym(handle, symbol);
}

typedef struct {
    void* my_openat;
    void* my_open;
    void* my_fopen;
    void* my_syscall;
    void* my_dl_iterate_phdr;
    void* my_access;
    void* my_faccessat;
    void* my_readlink;
    void* my_readlinkat;
    void* my_stat;
    void* my_lstat;
    void* my_statfs;
    void* my_opendir;
    void* my_popen;
    void* my_dladdr;
    void* my_dlopen;
    void* my_sysprop_get;
    void* my_sysprop_find;
    int patched;
} ctx_t;

static uintptr_t norm_ptr(uintptr_t base, uintptr_t p) {
    return p < base ? p + base : p;
}

static void do_patch_dyn(uintptr_t base, const Elf64_Dyn* dyn, ctx_t* ctx) {
    const char* strtab = 0;
    const Elf64_Sym* symtab = 0;
    const Elf64_Rela* jmprel = 0;
    uint64_t pltrelsz = 0;
    const Elf64_Rela* rela = 0;
    uint64_t relasz = 0;
    for (const Elf64_Dyn* d = dyn; d->d_tag != 0; d++) {
        switch (d->d_tag) {
            case DT_STRTAB: strtab = (const char*)(d->d_val); break;
            case DT_SYMTAB: symtab = (const Elf64_Sym*)(d->d_val); break;
            case DT_JMPREL: jmprel = (const Elf64_Rela*)(d->d_val); break;
            case DT_PLTRELSZ: pltrelsz = d->d_val; break;
            case DT_RELA: rela = (const Elf64_Rela*)(d->d_val); break;
            case DT_RELASZ: relasz = d->d_val; break;
        }
    }
    if (!strtab || !symtab) return;
    strtab = (const char*)norm_ptr(base, (uintptr_t)strtab);
    symtab = (const Elf64_Sym*)norm_ptr(base, (uintptr_t)symtab);

    const Elf64_Rela* tabs[2];
    uint64_t szs[2];
    tabs[0] = jmprel; szs[0] = pltrelsz;
    tabs[1] = rela; szs[1] = relasz;
    for (int t = 0; t < 2; t++) {
        if (!tabs[t] || !szs[t]) continue;
        const Elf64_Rela* rt = (const Elf64_Rela*)norm_ptr(base, (uintptr_t)tabs[t]);
        uint64_t count = szs[t] / sizeof(Elf64_Rela);
        for (uint64_t i = 0; i < count; i++) {
            uint64_t sym = ELF64_R_SYM(rt[i].r_info);
            const char* nm = strtab + symtab[sym].st_name;
            void* repl = 0;
            if (strcmp(nm, "openat") == 0 || strcmp(nm, "openat64") == 0) repl = ctx->my_openat;
            else if (strcmp(nm, "open") == 0 || strcmp(nm, "open64") == 0) repl = ctx->my_open;
            else if (strcmp(nm, "fopen") == 0) repl = ctx->my_fopen;
            else if (strcmp(nm, "syscall") == 0) repl = ctx->my_syscall;
            else if (strcmp(nm, "dl_iterate_phdr") == 0) repl = ctx->my_dl_iterate_phdr;
            else if (strcmp(nm, "access") == 0) repl = ctx->my_access;
            else if (strcmp(nm, "faccessat") == 0) repl = ctx->my_faccessat;
            else if (strcmp(nm, "readlink") == 0) repl = ctx->my_readlink;
            else if (strcmp(nm, "readlinkat") == 0) repl = ctx->my_readlinkat;
            else if (strcmp(nm, "stat") == 0) repl = ctx->my_stat;
            else if (strcmp(nm, "lstat") == 0) repl = ctx->my_lstat;
            else if (strcmp(nm, "statfs") == 0) repl = ctx->my_statfs;
            else if (strcmp(nm, "opendir") == 0) repl = ctx->my_opendir;
            else if (strcmp(nm, "popen") == 0) repl = ctx->my_popen;
            else if (strcmp(nm, "dladdr") == 0) repl = ctx->my_dladdr;
            else if (strcmp(nm, "dlopen") == 0) repl = ctx->my_dlopen;
            else if (strcmp(nm, "__system_property_get") == 0) repl = ctx->my_sysprop_get;
            else if (strcmp(nm, "__system_property_find") == 0) repl = ctx->my_sysprop_find;
            if (!repl) continue;
            void** got = (void**)(base + rt[i].r_offset);
            uintptr_t pg = (uintptr_t)got & ~(g_page - 1);
            if (mprotect((void*)pg, (size_t)g_page, PROT_READ | PROT_WRITE) != 0) continue;
            *got = repl;
            mprotect((void*)pg, (size_t)g_page, PROT_READ);
            ctx->patched++;
        }
    }
}

static int elf_ok(uintptr_t base) {
    const Elf64_Ehdr* eh = (const Elf64_Ehdr*)base;
    return eh->e_ident[0] == 0x7f && eh->e_ident[1] == 'E' && eh->e_ident[2] == 'L'
            && eh->e_ident[3] == 'F' && eh->e_ident[4] == 2;
}

static uintptr_t load_bias(uintptr_t map_base) {
    const Elf64_Ehdr* eh = (const Elf64_Ehdr*)map_base;
    const Elf64_Phdr* ph = (const Elf64_Phdr*)(map_base + eh->e_phoff);
    for (int i = 0; i < eh->e_phnum; i++) {
        if (ph[i].p_type == PT_LOAD) return map_base - ph[i].p_vaddr;
    }
    return map_base;
}

static uintptr_t g_logged_bases[8];
static int g_logged_n;
static int g_last_patched = -1;

static int already_logged(uintptr_t base) {
    for (int i = 0; i < g_logged_n; i++) if (g_logged_bases[i] == base) return 1;
    if (g_logged_n < 8) g_logged_bases[g_logged_n++] = base;
    return 0;
}

static void patch_module_at(uintptr_t map_base, ctx_t* ctx) {
    if (!elf_ok(map_base)) return;
    if (!already_logged(map_base)) LOGI("patching detector maps base %p", (void*)map_base);
    uintptr_t bias = load_bias(map_base);
    const Elf64_Ehdr* eh = (const Elf64_Ehdr*)map_base;
    const Elf64_Phdr* ph = (const Elf64_Phdr*)(map_base + eh->e_phoff);
    for (int i = 0; i < eh->e_phnum; i++) {
        if (ph[i].p_type != PT_DYNAMIC) continue;
        const Elf64_Dyn* dyn = (const Elf64_Dyn*)(bias + ph[i].p_vaddr);
        do_patch_dyn(bias, dyn, ctx);
    }
}

static uintptr_t parse_hex(const char* s, const char** end) {
    uintptr_t v = 0;
    while (*s) {
        char c = *s;
        int n = -1;
        if (c >= '0' && c <= '9') n = c - '0';
        else if (c >= 'a' && c <= 'f') n = c - 'a' + 10;
        else if (c >= 'A' && c <= 'F') n = c - 'A' + 10;
        else break;
        v = (v << 4) | (uintptr_t)n;
        s++;
    }
    if (end) *end = s;
    return v;
}

static void for_each_maps_line(void (*fn)(char* line, size_t len, void* arg), void* arg) {
    long fd = raw_svc(SYS_openat, (long)AT_FDCWD, (long)"/proc/self/maps", (long)O_RDONLY, 0L, 0L, 0L);
    if (fd < 0) return;
    char buf[8192];
    char line[1024];
    size_t ll = 0;
    long r;
    while ((r = raw_svc(SYS_read, fd, (long)buf, (long)sizeof(buf), 0, 0, 0)) > 0) {
        for (long i = 0; i < r; i++) {
            char c = buf[i];
            if (ll < sizeof(line) - 1) line[ll++] = c;
            if (c == '\n') {
                line[ll] = 0;
                fn(line, ll, arg);
                ll = 0;
            }
        }
    }
    raw_svc(SYS_close, fd, 0, 0, 0, 0, 0);
}

static void patch_line(char* line, size_t ll, void* arg) {
    ctx_t* ctx = (ctx_t*)arg;
    if (contains(line, ll, " 00000000 ") && is_detector_path(line)) {
        const char* rest;
        uintptr_t start = parse_hex(line, &rest);
        if (start && rest && *rest == '-') patch_module_at(start, ctx);
    }
}

static void locate_line(char* line, size_t ll, void* arg) {
    uintptr_t here = (uintptr_t)arg;
    const char* rest;
    uintptr_t start = parse_hex(line, &rest);
    if (!start || !rest || *rest != '-') return;
    rest++;
    uintptr_t end = parse_hex(rest, 0);
    if (here >= start && here < end) {
        g_text_lo = start;
        g_text_hi = end;
    }
}

static void name_line(char* line, size_t ll, void* arg) {
    (void)arg;
    if (!contains(line, ll, " r-xp ") && !contains(line, ll, " r-xp\t")) return;
    int has_path = 0;
    for (size_t i = 0; i < ll; i++) {
        if (line[i] == '/' || line[i] == '[') { has_path = 1; break; }
    }
    if (has_path) return;
    const char* rest;
    uintptr_t start = parse_hex(line, &rest);
    if (!start || !rest || *rest != '-') return;
    rest++;
    uintptr_t end = parse_hex(rest, 0);
    if (end <= start) return;
    long rc = raw_svc(SYS_prctl, PR_SET_VMA, PR_SET_VMA_ANON_NAME,
            (long)start, (long)(end - start), (long)"dalvik-jit-code-cache", 0);
    if (rc == 0) g_named_rx++;
}

static void patch_from_maps(ctx_t* ctx) {
    for_each_maps_line(patch_line, ctx);
}

static void name_anon_rx(void) {
    int before = g_named_rx;
    for_each_maps_line(name_line, 0);
    if (g_named_rx != before) LOGI("named %d nameless RX as dalvik-jit-code-cache", g_named_rx);
}

static int iter_cb(void* info_v, size_t size, void* data) {
    (void)size;
    dl_phdr_info_min* info = (dl_phdr_info_min*)info_v;
    ctx_t* ctx = (ctx_t*)data;
    const char* nm = info->dlpi_name ? info->dlpi_name : "";
    if (!is_detector_path(nm)) return 0;
    patch_module_at(info->dlpi_addr, ctx);
    return 0;
}

static long cloak_openat(long dirfd, const char* path, long flags, long mode) {
    char resolved[768];
    const char* ep = effective_path((int)dirfd, path, resolved, sizeof(resolved));
    if (is_proc_exposure_path(ep)) {
        int f = filtered_proc_fd(ep);
        if (f >= 0) return f;
    }
    if (path_denied(ep) || path_denied(path)) return -ENOENT;
    return raw_svc(SYS_openat, dirfd, (long)path, flags, mode, 0, 0);
}

static void on_sigsys(int sig, siginfo_t* si, void* uctx) {
    (void)sig;
    (void)si;
    ucontext_t* uc = (ucontext_t*)uctx;
    uint64_t* r = uc->uc_mcontext.regs;
    long nr = (long)r[8];
    long ret;
    if (nr == SYS_openat) {
        ret = cloak_openat((long)r[0], (const char*)r[1], (long)r[2], (long)r[3]);
    } else if (nr == SYS_openat2) {
        char resolved[768];
        const char* path = effective_path((int)r[0], (const char*)r[1], resolved, sizeof(resolved));
        if (is_proc_exposure_path(path)) {
            int f = filtered_proc_fd(path);
            ret = f >= 0 ? f : raw_svc(nr, (long)r[0], (long)r[1], (long)r[2], (long)r[3], (long)r[4], (long)r[5]);
        } else if (path_denied(path) || path_denied((const char*)r[1])) ret = -ENOENT;
        else ret = raw_svc(nr, (long)r[0], (long)r[1], (long)r[2], (long)r[3], (long)r[4], (long)r[5]);
    } else if (nr == SYS_faccessat || nr == SYS_faccessat2) {
        char resolved[768];
        const char* path = effective_path((int)r[0], (const char*)r[1], resolved, sizeof(resolved));
        if (path_denied(path) || path_denied((const char*)r[1])) ret = -ENOENT;
        else if (is_proc_exposure_path(path)) ret = 0;
        else ret = raw_svc(nr, (long)r[0], (long)r[1], (long)r[2], (long)r[3], (long)r[4], (long)r[5]);
    } else if (nr == SYS_newfstatat || nr == SYS_statx || nr == SYS_readlinkat) {
        char resolved[768];
        const char* path = effective_path((int)r[0], (const char*)r[1], resolved, sizeof(resolved));
        if (path_denied(path) || path_denied((const char*)r[1])) ret = -ENOENT;
        else ret = raw_svc(nr, (long)r[0], (long)r[1], (long)r[2], (long)r[3], (long)r[4], (long)r[5]);
    } else {
        ret = raw_svc(nr, (long)r[0], (long)r[1], (long)r[2], (long)r[3], (long)r[4], (long)r[5]);
    }
    r[0] = (unsigned long)ret;
}

static int install_seccomp(void) {
    if (g_seccomp_on) return g_seccomp_on > 0;
    for_each_maps_line(locate_line, (void*)(uintptr_t)raw_svc);
    if (!g_text_lo || g_text_hi <= g_text_lo) {
        LOGW("seccomp skip: text range missing");
        return 0;
    }
    if ((g_text_lo >> 32) != (g_text_hi >> 32)) {
        LOGW("seccomp skip: text crosses 4GiB");
        return 0;
    }
    uint32_t lo32 = (uint32_t)g_text_lo;
    uint32_t hi32 = (uint32_t)g_text_hi;
    uint32_t hi4g = (uint32_t)(g_text_lo >> 32);
    uint32_t nrs[] = {
        SYS_openat, SYS_faccessat, SYS_faccessat2,
        SYS_newfstatat, SYS_readlinkat, SYS_statx, SYS_openat2
    };
    struct sock_filter filt[64];
    int n = 0;
    filt[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 4);
    filt[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0);
    filt[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    filt[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 0);
    int nr_base = n;
    for (unsigned i = 0; i < sizeof(nrs) / sizeof(nrs[0]); i++) {
        /* placeholder jumps, patched below */
        filt[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, nrs[i], 0, 0);
    }
    int allow_idx = n;
    (void)allow_idx;
    filt[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    int ip_idx = n;
    filt[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 12);
    filt[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, hi4g, 1, 0);
    filt[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
    filt[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 8);
    filt[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, lo32, 1, 0);
    filt[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
    filt[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, hi32, 0, 1);
    filt[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
    filt[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    int nr_count = (int)(sizeof(nrs) / sizeof(nrs[0]));
    for (int i = 0; i < nr_count; i++) {
        int insn = nr_base + i;
        int remaining = nr_count - 1 - i;
        /* if match, skip remaining JEQ + ALLOW, land on ip_idx */
        filt[insn].jt = (uint8_t)(remaining + 1);
        filt[insn].jf = 0;
    }
    struct sigaction sa;
    for (unsigned i = 0; i < sizeof(sa); i++) ((char*)&sa)[i] = 0;
    sa.sa_sigaction = on_sigsys;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    if (sigaction(SIGSYS, &sa, 0) != 0) {
        LOGW("seccomp sigaction failed");
        return 0;
    }
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        LOGW("seccomp no_new_privs failed");
        return 0;
    }
    struct sock_fprog prog;
    for (unsigned i = 0; i < sizeof(prog); i++) ((char*)&prog)[i] = 0;
    prog.len = (unsigned short)n;
    prog.filter = filt;
    long rc = raw_svc(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC,
            (long)&prog, 0, 0, 0);
    if (rc != 0) {
        rc = raw_svc(SYS_prctl, PR_SET_SECCOMP, SECCOMP_MODE_FILTER, (long)&prog, 0, 0, 0);
    }
    if (rc != 0) {
        LOGW("seccomp install failed rc=%ld (maps already renamed; not fatal)", rc);
        g_seccomp_on = -1;
        return 0;
    }
    g_seccomp_on = 1;
    LOGI("seccomp cloak on text %p-%p", (void*)g_text_lo, (void*)g_text_hi);
    return 1;
}

int Java_com_onebot_qq_qq_MapsHide_install(void* env, void* clazz) {
    (void)env;
    (void)clazz;
    g_page = sysconf(39);
    if (g_page <= 0) g_page = 4096;
    ctx_t ctx;
    ctx.my_openat = (void*)my_openat;
    ctx.my_open = (void*)my_open;
    ctx.my_fopen = (void*)my_fopen;
    ctx.my_syscall = (void*)my_syscall;
    ctx.my_dl_iterate_phdr = (void*)my_dl_iterate_phdr;
    ctx.my_access = (void*)my_access;
    ctx.my_faccessat = (void*)my_faccessat;
    ctx.my_readlink = (void*)my_readlink;
    ctx.my_readlinkat = (void*)my_readlinkat;
    ctx.my_stat = (void*)my_stat;
    ctx.my_lstat = (void*)my_lstat;
    ctx.my_statfs = (void*)my_statfs;
    ctx.my_opendir = (void*)my_opendir;
    ctx.my_popen = (void*)my_popen;
    ctx.my_dladdr = (void*)my_dladdr;
    ctx.my_dlopen = (void*)my_dlopen;
    ctx.my_sysprop_get = (void*)my_sysprop_get;
    ctx.my_sysprop_find = (void*)my_sysprop_find;
    ctx.patched = 0;
    patch_from_maps(&ctx);
    dl_iterate_phdr(iter_cb, &ctx);
    name_anon_rx();
    install_seccomp();
    if (ctx.patched != g_last_patched) {
        LOGI("maps filter installed, patched %d GOT slots seccomp=%d named_rx=%d",
                ctx.patched, g_seccomp_on, g_named_rx);
        g_last_patched = ctx.patched;
    }
    return ctx.patched;
}
