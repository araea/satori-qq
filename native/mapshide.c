// mapshide.c — detector-lib GOT filter + in-process seccomp for bare svc.
//
// v4 (root punch-through): nameless injection RX is renamed to look like ART
// jit; so loads from memfd:jit-cache; GOT still patches fekit/ckguard libc.
// Seccomp TRAP of bare openat svc: classic BPF jumps MUST use
// BPF_JMP|BPF_JEQ|BPF_K (not BPF_JEQ|BPF_K). Missing BPF_JMP → code 0x10 →
// EINVAL. Filter is in the running APK; JNI install() is the glue.
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

#define LOGI(...) __android_log_print(4, "Q.Maps", __VA_ARGS__)
#define LOGW(...) __android_log_print(5, "Q.Maps", __VA_ARGS__)

#define SYS_openat        56
#define SYS_read          63
#define SYS_close         57
#define SYS_lseek         62
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
    "vector", "zygisk", "xposed", "lspd", "lsposed", "riru", "magisk",
    "mapshide", "libmapshide", "onebot", "/data/adb", "EdXposed", "substrate",
    "debug_ramdisk", "/system/bin/su", "/system/xbin/su", "kernelsu", 0
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

static int is_proc_exposure_path(const char* p) {
    if (!p) return 0;
    if (!strstr(p, "/proc/")) return 0;
    return strstr(p, "/maps") || strstr(p, "/smaps") || strstr(p, "/mountinfo");
}

static int path_denied(const char* p) {
    if (!p) return 0;
    size_t n = strlen(p);
    for (int i = 0; BLOCK[i]; i++) {
        if (contains(p, n, BLOCK[i])) return 1;
    }
    return 0;
}

static int is_detector_path(const char* p) {
    if (!p) return 0;
    return strstr(p, "fekit") || strstr(p, "ckguard") || strstr(p, "wtecdh");
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
                if (is_mapping_header(line, ll)) drop_stanza = line_blocked(line, ll);
                else if (!strstr(path, "/smaps")) drop_stanza = 0;
                if (!drop_stanza && !line_blocked(line, ll))
                    raw_svc(SYS_write, mfd, (long)line, (long)ll, 0, 0, 0);
                ll = 0;
            }
        }
    }
    if (ll > 0) {
        if (is_mapping_header(line, ll)) drop_stanza = line_blocked(line, ll);
        else if (!strstr(path, "/smaps")) drop_stanza = 0;
        if (!drop_stanza && !line_blocked(line, ll))
            raw_svc(SYS_write, mfd, (long)line, (long)ll, 0, 0, 0);
    }
    raw_svc(SYS_close, fd, 0, 0, 0, 0, 0);
    raw_svc(SYS_lseek, mfd, 0L, 0L, 0, 0, 0);
    return (int)mfd;
}

static int my_openat(int dirfd, const char* path, int flags, int mode) {
    if (is_proc_exposure_path(path)) {
        int f = filtered_proc_fd(path);
        if (f >= 0) return f;
    }
    if (path_denied(path)) return -ENOENT;
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
        const char* path = (const char*)a1;
        if (is_proc_exposure_path(path)) {
            int f = filtered_proc_fd(path);
            if (f >= 0) return f;
        }
        if (path_denied(path)) return -ENOENT;
    }
    if (n == SYS_faccessat || n == SYS_faccessat2 || n == SYS_newfstatat
            || n == SYS_readlinkat || n == SYS_statx) {
        const char* path = (const char*)a1;
        if (path_denied(path)) return -ENOENT;
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
    if (path_denied(path)) return -ENOENT;
    if (is_proc_exposure_path(path)) return 0;
    return (int)raw_svc(SYS_faccessat, (long)dirfd, (long)path, (long)mode, (long)flags, 0, 0);
}

static long my_readlink(const char* path, char* buf, unsigned long bufsz) {
    if (path_denied(path)) return -ENOENT;
    return raw_svc(SYS_readlinkat, (long)AT_FDCWD, (long)path, (long)buf, (long)bufsz, 0, 0);
}

static long my_readlinkat(int dirfd, const char* path, char* buf, unsigned long bufsz) {
    if (path_denied(path)) return -ENOENT;
    return raw_svc(SYS_readlinkat, (long)dirfd, (long)path, (long)buf, (long)bufsz, 0, 0);
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
    if (is_proc_exposure_path(path)) {
        int f = filtered_proc_fd(path);
        if (f >= 0) return f;
    }
    if (path_denied(path)) return -ENOENT;
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
        const char* path = (const char*)r[1];
        if (is_proc_exposure_path(path)) {
            int f = filtered_proc_fd(path);
            ret = f >= 0 ? f : raw_svc(nr, (long)r[0], (long)r[1], (long)r[2], (long)r[3], (long)r[4], (long)r[5]);
        } else if (path_denied(path)) ret = -ENOENT;
        else ret = raw_svc(nr, (long)r[0], (long)r[1], (long)r[2], (long)r[3], (long)r[4], (long)r[5]);
    } else if (nr == SYS_faccessat || nr == SYS_faccessat2) {
        const char* path = (const char*)r[1];
        if (path_denied(path)) ret = -ENOENT;
        else if (is_proc_exposure_path(path)) ret = 0;
        else ret = raw_svc(nr, (long)r[0], (long)r[1], (long)r[2], (long)r[3], (long)r[4], (long)r[5]);
    } else if (nr == SYS_newfstatat || nr == SYS_statx || nr == SYS_readlinkat) {
        const char* path = (const char*)r[1];
        if (path_denied(path)) ret = -ENOENT;
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
