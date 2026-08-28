// mapshide.c — surgical /proc/self/maps filter aimed at libfekit.
//
// v1 used dl_iterate_phdr from this module's linker namespace and patched 0 GOT slots
// because it could not see QQ's libfekit.so. v2 locates detector libraries by parsing
// /proc/self/maps (same address space, namespace-independent) and patches their GOT.
//
// Symbols replaced inside detector libs only: open, openat, fopen, syscall.
// Our own I/O uses raw svc wrappers via libc syscall() from THIS .so's GOT, so it does
// not recurse through the patched detector slots.
//
// HONEST LIMITATION: a direct `svc #openat` inside libfekit still bypasses GOT. We also
// patch the libc `syscall` wrapper, which covers syscall(SYS_openat, ...).

#include <stdint.h>
#include <stddef.h>

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
extern long syscall(long number, ...);

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
#define AT_FDCWD          (-100)
#define O_RDONLY          0
#define PROT_READ  1
#define PROT_WRITE 2

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
    "mapshide", "libmapshide", "onebot", "/data/adb", "EdXposed", "substrate", 0
};

static long g_page = 4096;

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
    // Anonymous RWX is the remaining Zygisk/Vector trampoline fingerprint after
    // path-hiding. Detector libs should not see it. Named mappings with rwx (rare)
    // are left alone.
    if (contains(line, len, " rwxp ") || contains(line, len, " rwxp\t")) {
        int has_path = 0;
        for (size_t i = 0; i < len; i++) {
            if (line[i] == '/' || line[i] == '[') { has_path = 1; break; }
        }
        if (!has_path) return 1;
    }
    return 0;
}

static int is_maps_path(const char* p) {
    if (!p) return 0;
    if (!strstr(p, "/proc/")) return 0;
    return strstr(p, "maps") != 0;
}

static int is_detector_path(const char* p) {
    if (!p) return 0;
    return strstr(p, "fekit") || strstr(p, "ckguard") || strstr(p, "wtecdh");
}

static int filtered_maps_fd(void) {
    long fd = syscall(SYS_openat, (long)AT_FDCWD, "/proc/self/maps", (long)O_RDONLY, 0L);
    if (fd < 0) return -1;
    long mfd = syscall(SYS_memfd_create, "m", 0L);
    if (mfd < 0) { syscall(SYS_close, fd); return -1; }
    char buf[8192];
    char line[1024];
    size_t ll = 0;
    long r;
    while ((r = syscall(SYS_read, fd, buf, (long)sizeof(buf))) > 0) {
        for (long i = 0; i < r; i++) {
            char c = buf[i];
            if (ll < sizeof(line) - 1) line[ll++] = c;
            if (c == '\n') {
                if (!line_blocked(line, ll)) syscall(SYS_write, mfd, line, (long)ll);
                ll = 0;
            }
        }
    }
    if (ll > 0 && !line_blocked(line, ll)) syscall(SYS_write, mfd, line, (long)ll);
    syscall(SYS_close, fd);
    syscall(SYS_lseek, mfd, 0L, 0L);
    return (int)mfd;
}

static int my_openat(int dirfd, const char* path, int flags, int mode) {
    if (is_maps_path(path)) {
        int f = filtered_maps_fd();
        if (f >= 0) return f;
    }
    return (int)syscall(SYS_openat, (long)dirfd, (long)path, (long)flags, (long)mode);
}

static int my_open(const char* path, int flags, int mode) {
    return my_openat(AT_FDCWD, path, flags, mode);
}

static FILE* my_fopen(const char* path, const char* mode) {
    if (is_maps_path(path)) {
        int f = filtered_maps_fd();
        if (f >= 0) {
            FILE* fp = fdopen(f, mode ? mode : "r");
            if (fp) return fp;
            syscall(SYS_close, (long)f);
        }
    }
    return fopen(path, mode);
}

static long my_syscall(long n, long a0, long a1, long a2, long a3, long a4, long a5) {
    if (n == SYS_openat) {
        const char* path = (const char*)a1;
        if (is_maps_path(path)) {
            int f = filtered_maps_fd();
            if (f >= 0) return f;
        }
    }
    return syscall(n, a0, a1, a2, a3, a4, a5);
}

typedef struct {
    void* my_openat;
    void* my_open;
    void* my_fopen;
    void* my_syscall;
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

static void patch_from_maps(ctx_t* ctx) {
    long fd = syscall(SYS_openat, (long)AT_FDCWD, "/proc/self/maps", (long)O_RDONLY, 0L);
    if (fd < 0) return;
    char buf[8192];
    char line[1024];
    size_t ll = 0;
    long r;
    while ((r = syscall(SYS_read, fd, buf, (long)sizeof(buf))) > 0) {
        for (long i = 0; i < r; i++) {
            char c = buf[i];
            if (ll < sizeof(line) - 1) line[ll++] = c;
            if (c == '\n') {
                line[ll] = 0;
                // offset 00000000 + detector path => ELF header mapping
                if (contains(line, ll, " 00000000 ") && is_detector_path(line)) {
                    const char* rest;
                    uintptr_t start = parse_hex(line, &rest);
                    if (start && rest && *rest == '-') patch_module_at(start, ctx);
                }
                ll = 0;
            }
        }
    }
    syscall(SYS_close, fd);
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
    ctx.patched = 0;
    patch_from_maps(&ctx);
    dl_iterate_phdr(iter_cb, &ctx);
    if (ctx.patched != g_last_patched) {
        LOGI("maps filter installed, patched %d GOT slots", ctx.patched);
        g_last_patched = ctx.patched;
    }
    return ctx.patched;
}
