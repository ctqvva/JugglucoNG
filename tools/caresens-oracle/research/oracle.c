// Loads an arm64 Android shared object into this arm64 process (macOS or
// Linux) so a locally supplied reference library can be called directly.
#include <fcntl.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

typedef struct { uint8_t ident[16]; uint16_t type, machine; uint32_t version; uint64_t entry, phoff, shoff; uint32_t flags; uint16_t ehsize, phentsize, phnum, shentsize, shnum, shstrndx; } Ehdr;
typedef struct { uint32_t type, flags; uint64_t offset, vaddr, paddr, filesz, memsz, align; } Phdr;
typedef struct { int64_t tag; uint64_t val; } Dyn;
typedef struct { uint32_t name; uint8_t info, other; uint16_t shndx; uint64_t value, size; } Sym;
typedef struct { uint64_t offset, info; int64_t addend; } Rela;

static void no_op(void) {}
static int zero(void) { return 0; }
static void smashed(void) { fprintf(stderr, "oracle: stack check failed\n"); abort(); }
static void unresolved(void) { fprintf(stderr, "oracle: unresolved import called\n"); abort(); }
static void sincos_(double x, double *s, double *c) { *s = sin(x); *c = cos(x); }
static void *host(const char *n) {
  static const struct { const char *n; void *f; } t[] = {
      {"memset", memset}, {"memcpy", memcpy}, {"memmove", memmove}, {"exp", exp}, {"atan", atan},
      {"sincos", sincos_}, {"cos", cos}, {"sin", sin}, {"log", log}, {"pow", pow}, {"close", close},
      {"__stack_chk_fail", smashed}, {"__cxa_atexit", zero}, {"__cxa_finalize", no_op},
      {"__register_atfork", zero}, {"__android_log_print", zero}};
  for (unsigned i = 0; i < sizeof t / sizeof *t; i++) if (!strcmp(t[i].n, n)) return t[i].f;
  return unresolved;
}

typedef unsigned char (*algo_t)(void *, void *, void *, void *, void *, void *);
typedef struct { uint8_t *base; uint64_t size; const char *error; algo_t algo; } Lib;

#define FAIL(m) do { L->error = m; return L; } while (0)
Lib *oracle_open(const char *path) {
  Lib *L = calloc(1, sizeof *L);
  int fd = open(path, O_RDONLY);
  struct stat st;
  if (fd < 0 || fstat(fd, &st)) FAIL("cannot open library");
  uint8_t *f = mmap(0, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
  close(fd);
  Ehdr *e = (Ehdr *)f;
  if (memcmp(e->ident, "\x7f" "ELF\2\1", 6) || e->machine != 183) FAIL("not an arm64 ELF library");
  Phdr *p = (Phdr *)(f + e->phoff);
  uint64_t top = 0, pg = sysconf(_SC_PAGESIZE);
  Dyn *dyn = 0;
  for (int i = 0; i < e->phnum; i++) {
    if (p[i].type == 1 && p[i].vaddr + p[i].memsz > top) top = p[i].vaddr + p[i].memsz;
    if (p[i].type == 2) dyn = (Dyn *)(uintptr_t)p[i].vaddr;
  }
  // One extra page at the end serves as thread block for stack-guard reads.
  uint64_t tls = (top + pg - 1) / pg * pg;
  L->size = tls + pg;
  uint8_t *b = mmap(0, L->size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
  if (b == MAP_FAILED) FAIL("mmap failed");
  L->base = b;
  for (int i = 0; i < e->phnum; i++) if (p[i].type == 1) memcpy(b + p[i].vaddr, f + p[i].offset, p[i].filesz);
  uint64_t symtab = 0, strtab = 0, rela = 0, relasz = 0, jmp = 0, jmpsz = 0, hash = 0;
  for (Dyn *d = (Dyn *)(b + (uintptr_t)dyn); d->tag; d++) switch (d->tag) {
    case 5: strtab = d->val; break; case 6: symtab = d->val; break;
    case 7: rela = d->val; break; case 8: relasz = d->val; break;
    case 23: jmp = d->val; break; case 2: jmpsz = d->val; break;
  }
  (void)hash;
  Sym *syms = (Sym *)(b + symtab);
  const char *str = (char *)b + strtab;
  for (int k = 0; k < 2; k++) {
    Rela *r = (Rela *)(b + (k ? jmp : rela));
    for (uint64_t i = 0; i < (k ? jmpsz : relasz) / sizeof(Rela); i++) {
      uint64_t v;
      if ((r[i].info & 0xffffffff) == 1027) v = (uint64_t)b + r[i].addend;
      else {
        Sym *s = syms + (r[i].info >> 32);
        v = (s->shndx ? (uint64_t)b + s->value : (uint64_t)host(str + s->name)) + r[i].addend;
      }
      memcpy(b + r[i].offset, &v, 8);
    }
  }
  for (Sym *s = syms; (char *)s < str && (uint8_t*)s < b + strtab; s++)
    if (s->name < 100000 && !strcmp(str + s->name, "air1_opcal4_algorithm")) L->algo = (algo_t)(b + s->value);
  if (!L->algo) FAIL("air1_opcal4_algorithm not exported");
  // The host thread register is not Android's: point `mrs xN, TPIDR_EL0` at the spare page.
  for (int i = 0; i < e->phnum; i++) if (p[i].type == 1 && (p[i].flags & 1))
    for (uint64_t o = p[i].vaddr; o + 4 <= p[i].vaddr + p[i].filesz; o += 4) {
      uint32_t w; memcpy(&w, b + o, 4);
      if ((w & 0xffffffe0) == 0xd53bd040) {
        int64_t pages = (int64_t)(((uint64_t)b + tls) >> 12) - (int64_t)(((uint64_t)b + o) >> 12);
        w = 0x90000000 | (w & 31) | ((pages & 3) << 29) | (((pages >> 2) & 0x7ffff) << 5);
        memcpy(b + o, &w, 4);
      }
    }
  for (int i = 0; i < e->phnum; i++) if (p[i].type == 1 && (p[i].flags & 1)) {
    uint64_t s = p[i].vaddr / pg * pg, n = (p[i].vaddr + p[i].memsz + pg - 1) / pg * pg;
    __builtin___clear_cache((char *)b + s, (char *)b + n);
    if (mprotect(b + s, n - s, PROT_READ | PROT_EXEC)) FAIL("mprotect failed");
  }
  munmap(f, st.st_size);
  return L;
}
const char *oracle_error(Lib *L) { return L->error; }
int oracle_step(Lib *L, void *dev, void *in, void *cal, void *args, void *out, void *dbg) {
  return L->algo(dev, in, cal, args, out, dbg);
}
void *oracle_base(Lib *L) { return L->base; }
void oracle_run(Lib *L, void *dev, uint8_t *in, void *cal, uint8_t *args, uint8_t *out, uint8_t *dbg, uint8_t *res, uint8_t *snap, int n) {
  for (int i = 0; i < n; i++) {
    res[i] = L->algo(dev, in + 74L * i, cal, args, out + 155L * i, dbg + 1579L * i);
    if (snap) memcpy(snap + 117312L * i, args, 117312);
  }
}
