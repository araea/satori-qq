// mapshide.c — best-effort native /proc/self/maps filter for QQ anti-detection.
//
// Loaded into QQ's process by the Xposed module. Installs GOT/PLT hooks on open/openat
// across all loaded ELF images so that any code reading /proc/self/maps (and friends) gets
// a FILTERED copy with hook-framework lines (vector/xposed/zygisk/lspd/riru/magisk/our .so)
// removed — starving QQ's Java- and libc-level environment scans.
//
// HONEST LIMITATION: a professional native anti-tamper (libfekit.so / QSec) may read maps via
// RAW SYSCALLS (svc #openat), bypassing libc entirely — GOT hooking cannot catch that. So this
// is best-effort and may not fully stop the kick. Defeating raw-syscall reads needs syscall/inline
// hooking, a much bigger and more fragile effort. See docs/ANTIDETECT.md.

#include <stdint.h>
#include <stddef.h>

// --- bionic prototypes (we build with -nostdlib and link libc/liblog/libdl) ---
typedef struct { const char* dlpi_name; uintptr_t dlpi_addr; const void* dlpi_phdr; uint16_t dlpi_phnum; } dl_phdr_info_min;
extern int dl_iterate_phdr(int (*cb)(void*, size_t, void*), void* data);
extern void* dlsym(void* handle, const char* name);
extern long syscall(long number, ...);
extern int   __android_log_print(int prio, const char* tag, const char* fmt, ...);
extern int   mprotect(void* addr, size_t len, int prot);
extern long  sysconf(int name);
extern char* strstr(const char* h, const char* n);
extern size_t strlen(const char* s);
extern void* memcpy(void* d, const void* s, size_t n);

#define RTLD_DEFAULT ((void*)0)
#define LOGI(...) __android_log_print(4, "MapsHide", __VA_ARGS__)
#define LOGW(...) __android_log_print(5, "MapsHide", __VA_ARGS__)

// arm64 syscall numbers
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
#define PROT_EXEC  4

// ELF64 minimal defs
typedef struct { uint32_t p_type; uint32_t p_flags; uint64_t p_offset,p_vaddr,p_paddr,p_filesz,p_memsz,p_align; } Elf64_Phdr;
typedef struct { int64_t d_tag; uint64_t d_val; } Elf64_Dyn;
typedef struct { uint32_t st_name; uint8_t st_info,st_other; uint16_t st_shndx; uint64_t st_value,st_size; } Elf64_Sym;
typedef struct { uint64_t r_offset; uint64_t r_info; int64_t r_addend; } Elf64_Rela;
#define PT_DYNAMIC 2
#define DT_STRTAB 5
#define DT_SYMTAB 6
#define DT_RELA   7
#define DT_RELASZ 8
#define DT_JMPREL 23
#define DT_PLTRELSZ 2
#define ELF64_R_SYM(i)  ((i) >> 32)

static const char* BLOCK[] = {
    "vector", "zygisk", "xposed", "lspd", "lsposed", "riru", "magisk",
    "mapshide", "libmapshide", "onebot", "/data/adb", "EdXposed", "substrate", 0
};

// real openat, resolved before hooking (bypasses our own GOT patch)
static long (*g_real_syscall)(long, ...) = 0;

static int line_blocked(const char* line, size_t len) {
    for (int i = 0; BLOCK[i]; i++) {
        // naive substring search bounded by len
        const char* b = BLOCK[i]; size_t bl = strlen(b);
        for (size_t j = 0; j + bl <= len; j++) {
            size_t k = 0; while (k < bl && line[j+k] == b[k]) k++;
            if (k == bl) return 1;
        }
    }
    return 0;
}

static int is_maps_path(const char* p) {
    if (!p) return 0;
    // /proc/... .../maps or /smaps
    if (!strstr(p, "/proc/")) return 0;
    size_t n = strlen(p);
    if (n >= 5 && (p[n-5]=='/'? 0:1)) {}
    return (n >= 4 && strstr(p, "maps")) ? 1 : 0;
}

// build a memfd holding the filtered maps; return its fd or -1
static int filtered_maps_fd(void) {
    long fd = syscall(SYS_openat, (long)AT_FDCWD, "/proc/self/maps", (long)O_RDONLY, 0L);
    if (fd < 0) return -1;
    long mfd = syscall(SYS_memfd_create, "m", 0L);
    if (mfd < 0) { syscall(SYS_close, fd); return -1; }
    static char buf[8192];
    char line[1024]; size_t ll = 0;
    long r;
    while ((r = syscall(SYS_read, fd, buf, (long)sizeof(buf))) > 0) {
        for (long i = 0; i < r; i++) {
            char c = buf[i];
            if (ll < sizeof(line)-1) line[ll++] = c;
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

// our replacement for openat(int dirfd, const char* path, int flags, mode_t mode)
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

static uintptr_t g_self_base = 0;
static long g_page = 4096;

static int patch_got(uintptr_t base, const char* soname) {
    if (base == g_self_base) return 0;
    // skip linker & our own lib
    if (soname && (strstr(soname,"mapshide") || strstr(soname,"linker") || strstr(soname,"libdl"))) return 0;

    // find PT_DYNAMIC by walking phdrs is complex without phdr access here; use dl_iterate_phdr caller.
    return 0;
}

typedef struct { const char* name; void* my_openat; void* my_open; int patched; } ctx_t;

static void do_patch_dyn(uintptr_t base, const Elf64_Dyn* dyn, ctx_t* ctx) {
    const char* strtab = 0; const Elf64_Sym* symtab = 0;
    const Elf64_Rela* jmprel = 0; uint64_t pltrelsz = 0;
    const Elf64_Rela* rela = 0; uint64_t relasz = 0;
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
    // some entries are absolute, some are base-relative; normalize (bionic gives absolute for loaded libs)
    #define NORM(p) ((uintptr_t)(p) < base ? (uintptr_t)(p) + base : (uintptr_t)(p))
    strtab = (const char*)NORM(strtab); symtab = (const Elf64_Sym*)NORM(symtab);

    const Elf64_Rela* tabs[2]; uint64_t szs[2];
    tabs[0]=jmprel; szs[0]=pltrelsz; tabs[1]=rela; szs[1]=relasz;
    for (int t = 0; t < 2; t++) {
        if (!tabs[t] || !szs[t]) continue;
        const Elf64_Rela* rt = (const Elf64_Rela*)NORM(tabs[t]);
        uint64_t count = szs[t] / sizeof(Elf64_Rela);
        for (uint64_t i = 0; i < count; i++) {
            uint64_t sym = ELF64_R_SYM(rt[i].r_info);
            const char* nm = strtab + symtab[sym].st_name;
            void* repl = 0;
            if (nm[0]=='o'&&nm[1]=='p'&&nm[2]=='e'&&nm[3]=='n') {
                if (nm[4]=='a'&&nm[5]=='t'&&nm[6]==0) repl = ctx->my_openat;   // openat
                else if (nm[4]==0) repl = ctx->my_open;                        // open
            }
            if (!repl) continue;
            void** got = (void**)(base + rt[i].r_offset);
            uintptr_t pg = (uintptr_t)got & ~(g_page-1);
            mprotect((void*)pg, g_page, PROT_READ|PROT_WRITE);
            *got = repl;
            ctx->patched++;
        }
    }
}

static int iter_cb(void* info_v, size_t size, void* data) {
    (void)size;
    dl_phdr_info_min* info = (dl_phdr_info_min*)info_v;
    ctx_t* ctx = (ctx_t*)data;
    const Elf64_Phdr* ph = (const Elf64_Phdr*)info->dlpi_phdr;
    const char* nm = info->dlpi_name ? info->dlpi_name : "";
    // SURGICAL: only patch the detector libs. Never touch ART/libc/libkernel/QQ itself —
    // they legitimately read the REAL /proc/self/maps (ART GC/JIT/unwind); feeding them a
    // filtered copy hangs QQ. Only libfekit (QSec) / libckguard need to be blinded.
    if (!(strstr(nm, "fekit") || strstr(nm, "ckguard") || strstr(nm, "wtecdh"))) return 0;
    LOGI("patching detector lib: %s", nm);
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (ph[i].p_type == PT_DYNAMIC) {
            const Elf64_Dyn* dyn = (const Elf64_Dyn*)(info->dlpi_addr + ph[i].p_vaddr);
            do_patch_dyn(info->dlpi_addr, dyn, ctx);
        }
    }
    return 0;
}

// JNI export: com.onebot.qq.qq.MapsHide.install()
int Java_com_onebot_qq_qq_MapsHide_install(void* env, void* clazz) {
    (void)env; (void)clazz;
    g_page = sysconf(39 /*_SC_PAGESIZE*/); if (g_page <= 0) g_page = 4096;
    ctx_t ctx; ctx.name=0; ctx.my_openat=(void*)my_openat; ctx.my_open=(void*)my_open; ctx.patched=0;
    dl_iterate_phdr(iter_cb, &ctx);
    LOGI("maps filter installed, patched %d GOT slots", ctx.patched);
    return ctx.patched;
}
