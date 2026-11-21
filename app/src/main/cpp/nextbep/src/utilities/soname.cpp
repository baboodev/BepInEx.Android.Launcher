#include <utilities/soname.h>

#include <elf.h>
#include <fstream>
#include <vector>
#include <cstring>

bool copy_elf_with_soname(const std::filesystem::path &src,
                          const std::filesystem::path &dst,
                          const char *newSoname,
                          std::string *error)
{
    std::ifstream in(src, std::ios::binary);
    if (!in) {
        if (error) *error = "cannot open source";
        return false;
    }
    std::vector<char> buf((std::istreambuf_iterator<char>(in)),
                          std::istreambuf_iterator<char>());
    in.close();
    if (buf.size() < sizeof(Elf64_Ehdr)) {
        if (error) *error = "file too small";
        return false;
    }

    auto *ehdr = reinterpret_cast<Elf64_Ehdr *>(buf.data());
    if (std::memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0 ||
        ehdr->e_ident[EI_CLASS] != ELFCLASS64) {
        if (error) *error = "not ELF64";
        return false;
    }

    if (ehdr->e_phoff + ehdr->e_phnum * sizeof(Elf64_Phdr) > buf.size()) {
        if (error) *error = "bad program headers";
        return false;
    }

    Elf64_Dyn *dynamic = nullptr;
    size_t dynCount = 0;
    Elf64_Addr dynVaddr = 0;
    Elf64_Xword dynFilesz = 0;
    Elf64_Off dynOffset = 0;

    auto *phdr = reinterpret_cast<Elf64_Phdr *>(buf.data() + ehdr->e_phoff);
    for (int i = 0; i < ehdr->e_phnum; ++i) {
        if (phdr[i].p_type == PT_DYNAMIC) {
            dynVaddr = phdr[i].p_vaddr;
            dynFilesz = phdr[i].p_filesz;
            dynOffset = phdr[i].p_offset;
            if (dynOffset + dynFilesz > buf.size()) {
                if (error) *error = "dynamic out of range";
                return false;
            }
            dynamic = reinterpret_cast<Elf64_Dyn *>(buf.data() + dynOffset);
            dynCount = dynFilesz / sizeof(Elf64_Dyn);
            break;
        }
    }
    if (!dynamic) {
        if (error) *error = "no PT_DYNAMIC";
        return false;
    }

    Elf64_Addr strtabVaddr = 0;
    Elf64_Xword sonameStrOffset = 0;
    bool haveSoname = false;
    for (size_t i = 0; i < dynCount; ++i) {
        if (dynamic[i].d_tag == DT_NULL) break;
        if (dynamic[i].d_tag == DT_STRTAB) strtabVaddr = dynamic[i].d_un.d_ptr;
        if (dynamic[i].d_tag == DT_SONAME) {
            sonameStrOffset = dynamic[i].d_un.d_val;
            haveSoname = true;
        }
    }
    if (!haveSoname || strtabVaddr == 0) {
        if (error) *error = "no DT_SONAME/STRTAB";
        return false;
    }

    // Convert STRTAB vaddr → file offset via LOAD segments.
    Elf64_Off strtabFileOff = 0;
    bool mapped = false;
    for (int i = 0; i < ehdr->e_phnum; ++i) {
        if (phdr[i].p_type != PT_LOAD) continue;
        if (strtabVaddr >= phdr[i].p_vaddr &&
            strtabVaddr < phdr[i].p_vaddr + phdr[i].p_filesz) {
            strtabFileOff = phdr[i].p_offset + (strtabVaddr - phdr[i].p_vaddr);
            mapped = true;
            break;
        }
    }
    if (!mapped) {
        if (error) *error = "STRTAB not in LOAD";
        return false;
    }

    const size_t sonamePos = static_cast<size_t>(strtabFileOff + sonameStrOffset);
    if (sonamePos >= buf.size()) {
        if (error) *error = "soname out of range";
        return false;
    }
    const char *oldSoname = buf.data() + sonamePos;
    const size_t oldLen = strnlen(oldSoname, buf.size() - sonamePos);
    const size_t newLen = std::strlen(newSoname);
    if (newLen > oldLen) {
        if (error) *error = "new soname longer than old";
        return false;
    }
    std::memcpy(buf.data() + sonamePos, newSoname, newLen);
    std::memset(buf.data() + sonamePos + newLen, 0, oldLen - newLen);

    std::error_code ec;
    std::filesystem::create_directories(dst.parent_path(), ec);
    std::ofstream out(dst, std::ios::binary | std::ios::trunc);
    if (!out) {
        if (error) *error = "cannot write dest";
        return false;
    }
    out.write(buf.data(), static_cast<std::streamsize>(buf.size()));
    return static_cast<bool>(out);
}
