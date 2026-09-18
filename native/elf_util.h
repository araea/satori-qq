// 进程内解析已加载 ELF 的符号：.dynsym 精确查找 + .symtab 精确/前缀查找。
//
// 要点（都是实测踩出来的）：
// 1. libart.so 由运行时在自己的 linker namespace 里加载，应用 namespace 里
//    `dlopen("libart.so", RTLD_NOLOAD)` 拿不到句柄，所以走 dl_iterate_phdr。
//    Zygisk Next 的 memory-type=anonymous 会把文件映射变成匿名映射，
//    /proc/self/maps 里看不到路径，但 dl_iterate_phdr 的 dlpi_name 仍有全路径。
// 2. Android 16 的 libart.so 里，很多 ART 内部函数**只在 .symtab 里**，
//    而且带 `.__uniq.<hash>` 后缀（clang 的 unique-internal-linkage-names）。
//    所以 LSPlant 对这些符号走前缀匹配。只查 .dynsym 会全部落空。
// 3. 段表不在内存映射里，得从磁盘上重新把文件 map 进来读。
#pragma once

#include <link.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdint.h>
#include <cerrno>
#include <string.h>
#include <android/log.h>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>
#include <algorithm>

namespace elfx {

inline bool EndsWith(const char *s, std::string_view suffix) {
    if (s == nullptr) return false;
    size_t n = strlen(s);
    return n >= suffix.size() && memcmp(s + n - suffix.size(), suffix.data(), suffix.size()) == 0;
}

class Elf {
public:
    Elf() = default;
    ~Elf() { Unload(); }

    Elf(const Elf &) = delete;
    Elf &operator=(const Elf &) = delete;

    bool Load(std::string_view soname) {
        Unload();
        wanted_ = soname;
        if (!FindBase()) return false;
        if (!MapFile()) return false;
        ParseSections();
        __android_log_print(ANDROID_LOG_INFO, "SatoriElf",
                            "%s base=%p exact=%zu prefixable=%zu", path_.c_str(), (void *) base_,
                            exact_.size(), sorted_.size());
        return base_ != 0;
    }

    /** 精确查找，先 .dynsym 再 .symtab。 */
    void *Sym(const char *name) const {
        auto it = exact_.find(std::string_view(name));
        if (it == exact_.end() || it->second == 0) return nullptr;
        return reinterpret_cast<void *>(base_ + it->second);
    }

    /** 前缀查找：返回字典序最小的前缀匹配项（.symtab 优先）。 */
    void *SymPrefix(std::string_view prefix) const {
        auto it = std::lower_bound(sorted_.begin(), sorted_.end(), prefix,
                                   [](const Entry &e, std::string_view p) {
                                       return e.name < p;
                                   });
        if (it == sorted_.end()) return nullptr;
        if (it->name.compare(0, prefix.size(), prefix) != 0) return nullptr;
        return it->value == 0 ? nullptr : reinterpret_cast<void *>(base_ + it->value);
    }

    bool loaded() const { return base_ != 0; }
    const std::string &path() const { return path_; }
    uintptr_t base() const { return base_; }

private:
    struct Entry {
        std::string_view name;
        ElfW(Addr) value;
        bool operator<(std::string_view p) const { return name < p; }
    };

    void Unload() {
        exact_.clear();
        sorted_.clear();
        if (buf_ != nullptr) {
            munmap(buf_, buf_size_);
            buf_ = nullptr;
            buf_size_ = 0;
        }
    }

    bool FindBase() {
        base_ = 0;
        dl_iterate_phdr(&Elf::PhdrCallback, this);
        return base_ != 0;
    }

    static int PhdrCallback(struct dl_phdr_info *info, size_t, void *data) {
        auto *self = static_cast<Elf *>(data);
        if (!EndsWith(info->dlpi_name, self->wanted_)) return 0;
        self->base_ = info->dlpi_addr;
        self->path_ = info->dlpi_name;
        return 1;
    }

    bool MapFile() {
        int fd = open(path_.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            __android_log_print(ANDROID_LOG_ERROR, "SatoriElf", "open %s failed: %s",
                                path_.c_str(), strerror(errno));
            return false;
        }
        struct stat st{};
        if (fstat(fd, &st) != 0 || st.st_size <= 0) { close(fd); return false; }
        void *m = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);
        if (m == MAP_FAILED) {
            __android_log_print(ANDROID_LOG_ERROR, "SatoriElf", "mmap %s failed", path_.c_str());
            return false;
        }
        buf_ = static_cast<uint8_t *>(m);
        buf_size_ = static_cast<size_t>(st.st_size);
        return true;
    }

    void ParseSections() {
        auto *eh = reinterpret_cast<const ElfW(Ehdr) *>(buf_);
        if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0) return;
        auto *sh = reinterpret_cast<const ElfW(Shdr) *>(buf_ + eh->e_shoff);
        auto *shstr = reinterpret_cast<const char *>(buf_ + sh[eh->e_shstrndx].sh_offset);

        const ElfW(Shdr) *symtab = nullptr, *strtab = nullptr;
        const ElfW(Shdr) *dynsym = nullptr, *dynstr = nullptr;
        for (int i = 0; i < eh->e_shnum; ++i) {
            switch (sh[i].sh_type) {
                case SHT_SYMTAB:
                    if (strcmp(shstr + sh[i].sh_name, ".symtab") == 0) {
                        symtab = &sh[i];
                        strtab = &sh[sh[i].sh_link];
                    }
                    break;
                case SHT_DYNSYM:
                    if (dynsym == nullptr) {
                        dynsym = &sh[i];
                        dynstr = &sh[sh[i].sh_link];
                    }
                    break;
                default:
                    break;
            }
        }

        if (dynsym != nullptr) {
            AddSymbols(reinterpret_cast<const ElfW(Sym) *>(buf_ + dynsym->sh_offset),
                       dynsym->sh_size / sizeof(ElfW(Sym)),
                       reinterpret_cast<const char *>(buf_ + dynstr->sh_offset));
        }
        if (symtab != nullptr) {
            const char *names = reinterpret_cast<const char *>(buf_ + strtab->sh_offset);
            size_t count = symtab->sh_size / sizeof(ElfW(Sym));
            auto *syms = reinterpret_cast<const ElfW(Sym) *>(buf_ + symtab->sh_offset);
            for (size_t i = 0; i < count; ++i) {
                const ElfW(Sym) *s = &syms[i];
                if (s->st_name == 0 || s->st_value == 0) continue;
                unsigned type = ELF64_ST_TYPE(s->st_info);
                if (type != STT_FUNC && type != STT_OBJECT) continue;
                std::string_view name{names + s->st_name};
                // .symtab 覆盖 .dynsym：同名前缀更完整（带 .__uniq. 后缀的也能查到）
                exact_.insert_or_assign(name, s->st_value);
                sorted_.push_back({name, s->st_value});
            }
            std::sort(sorted_.begin(), sorted_.end(),
                      [](const Entry &a, const Entry &b) { return a.name < b.name; });
        }
    }

    void AddSymbols(const ElfW(Sym) *syms, size_t count, const char *names) {
        for (size_t i = 0; i < count; ++i) {
            const ElfW(Sym) *s = &syms[i];
            if (s->st_name == 0 || s->st_value == 0) continue;
            exact_.insert_or_assign(std::string_view{names + s->st_name}, s->st_value);
        }
    }

    std::string wanted_;
    std::string path_;
    uintptr_t base_ = 0;
    uint8_t *buf_ = nullptr;
    size_t buf_size_ = 0;
    std::unordered_map<std::string_view, ElfW(Addr)> exact_;
    std::vector<Entry> sorted_;
};

}  // namespace elfx
