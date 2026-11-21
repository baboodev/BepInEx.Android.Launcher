#pragma once
#include <filesystem>
#include <string>

// Copy an ELF shared object and rewrite DT_SONAME. newSoname must be no longer
// than the original soname string (in-place dynstr rewrite).
bool copy_elf_with_soname(const std::filesystem::path &src,
                          const std::filesystem::path &dst,
                          const char *newSoname,
                          std::string *error);
