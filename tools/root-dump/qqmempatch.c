#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

typedef struct {
    unsigned long off;
    unsigned int expected;
    unsigned int replacement;
    const char* name;
} Patch;

typedef struct {
    int force_register;
    int diag_only;
    int diag_samples;
    int diag_interval_ms;
} Options;

static const unsigned int ARM64_NOP = 0xd503201f;
static const unsigned int ARM64_MOV_W0_1 = 0x52800020;
static const unsigned int ARM64_RET = 0xd65f03c0;

static Patch patches[] = {
    {0x10ecd4, 0x37004ba8, ARM64_NOP, "post_payload_tbnz"},
    {0x10efcc, 0x350032f9, ARM64_NOP, "pre_materialize_cbnz_1"},
    {0x10efec, 0x350031f9, ARM64_NOP, "pre_materialize_cbnz_2"},
    {0x11cb84, 0x97ffb823, ARM64_NOP, "self_kill_callsite"},
    {0x10fb20, 0x54000a8c, ARM64_NOP, "qiniu_b_gt"},
    {0x129938, 0x54000141, ARM64_NOP, "sdk25_b_ne"},
    {0x129c58, 0xd10403ff, ARM64_MOV_W0_1, "payload_check_mov_true"},
    {0x129c5c, 0xf90063f8, ARM64_RET, "payload_check_ret"},
};

static Patch force_register_patches[] = {
    {0x10d41c, 0x36000140, ARM64_NOP, "interface20_force_register"},
};

static int sleep_ms(int ms) {
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    return nanosleep(&ts, NULL);
}

static int find_jiagu_base(const char* pid, unsigned long long* out_base) {
    char maps_path[128];
    snprintf(maps_path, sizeof(maps_path), "/proc/%s/maps", pid);
    FILE* maps = fopen(maps_path, "r");
    if (!maps) return -1;

    char line[4096];
    unsigned long long best = 0;
    while (fgets(line, sizeof(line), maps)) {
        if (strstr(line, "libjiagu_vip.so") == NULL) continue;
        unsigned long long start = 0, end = 0, fileoff = 0;
        char perms[8] = {0};
        if (sscanf(line, "%llx-%llx %7s %llx", &start, &end, perms, &fileoff) < 4) continue;
        if (start == 0 || end <= start) continue;
        if (best == 0 || start < best) best = start;
    }
    fclose(maps);
    if (best == 0) return -1;
    *out_base = best;
    return 0;
}

static int read_u32(int memfd, unsigned long long addr, unsigned int* out) {
    unsigned int value = 0;
    ssize_t n = pread(memfd, &value, sizeof(value), (off_t)addr);
    if (n != (ssize_t)sizeof(value)) return -1;
    *out = value;
    return 0;
}

static int read_u64(int memfd, unsigned long long addr, unsigned long long* out) {
    unsigned long long value = 0;
    ssize_t n = pread(memfd, &value, sizeof(value), (off_t)addr);
    if (n != (ssize_t)sizeof(value)) return -1;
    *out = value;
    return 0;
}

static int write_u32(int memfd, unsigned long long addr, unsigned int value) {
    ssize_t n = pwrite(memfd, &value, sizeof(value), (off_t)addr);
    return n == (ssize_t)sizeof(value) ? 0 : -1;
}

static void diag_slot(int memfd, unsigned long long base, unsigned long off, const char* name) {
    unsigned long long value = 0;
    unsigned long long addr = base + off;
    if (read_u64(memfd, addr, &value) != 0) {
        fprintf(stderr, "diag %s off=0x%lx addr=0x%llx read_failed=%s\n", name, off, addr, strerror(errno));
        return;
    }
    fprintf(stderr, "diag %s off=0x%lx addr=0x%llx value=0x%llx\n", name, off, addr, value);
    if (value != 0) {
        unsigned long long q0 = 0, q1 = 0, q2 = 0, q3 = 0;
        int ok0 = read_u64(memfd, value, &q0) == 0;
        int ok1 = read_u64(memfd, value + 8, &q1) == 0;
        int ok2 = read_u64(memfd, value + 16, &q2) == 0;
        int ok3 = read_u64(memfd, value + 24, &q3) == 0;
        if (ok0 || ok1 || ok2 || ok3) {
            fprintf(stderr, "diag %s.ptr qwords=%s0x%llx,%s0x%llx,%s0x%llx,%s0x%llx\n",
                    name,
                    ok0 ? "" : "?", q0,
                    ok1 ? "" : "?", q1,
                    ok2 ? "" : "?", q2,
                    ok3 ? "" : "?", q3);
        } else {
            fprintf(stderr, "diag %s.ptr read_failed\n", name);
        }
    }
}

static void print_jiagu_diag(int memfd, unsigned long long base) {
    fprintf(stderr, "diag_begin base=0x%llx\n", base);
    diag_slot(memfd, base, 0x253010, "payload_slot_253010");
    diag_slot(memfd, base, 0x253018, "payload_slot_253018");
    diag_slot(memfd, base, 0x253148, "token_manager_253148");
    diag_slot(memfd, base, 0x253150, "seed_table_253150");
    diag_slot(memfd, base, 0x2531b0, "registry_cache_2531b0");
    fprintf(stderr, "diag_end\n");
}

static void print_jiagu_diag_samples(int memfd, unsigned long long base, const Options* options) {
    int samples = options->diag_samples > 0 ? options->diag_samples : 1;
    int interval_ms = options->diag_interval_ms >= 0 ? options->diag_interval_ms : 0;
    for (int i = 0; i < samples; ++i) {
        fprintf(stderr, "diag_sample index=%d total=%d interval_ms=%d\n", i + 1, samples, interval_ms);
        print_jiagu_diag(memfd, base);
        if (i + 1 < samples && interval_ms > 0) {
            sleep_ms(interval_ms);
        }
    }
}

static int apply_patches(int memfd, unsigned long long base, Patch* patch_list, size_t count, int verbose, int* patched, int* ready, int* mismatched) {
    for (size_t i = 0; i < count; ++i) {
        Patch* p = &patch_list[i];
        unsigned long long addr = base + p->off;
        unsigned int before = 0;
        if (read_u32(memfd, addr, &before) != 0) {
            if (verbose) fprintf(stderr, "%s read failed addr=0x%llx: %s\n", p->name, addr, strerror(errno));
            continue;
        }
        if (before == p->replacement) {
            ++*ready;
            if (verbose) fprintf(stderr, "%s already patched addr=0x%llx\n", p->name, addr);
            continue;
        }
        if (before != p->expected) {
            ++*mismatched;
            if (verbose) {
                fprintf(stderr, "%s mismatch addr=0x%llx before=0x%08x expected=0x%08x\n",
                        p->name, addr, before, p->expected);
            }
            continue;
        }
        if (write_u32(memfd, addr, p->replacement) == 0) {
            ++*patched;
            ++*ready;
            fprintf(stderr, "%s patched addr=0x%llx 0x%08x -> 0x%08x\n",
                    p->name, addr, before, p->replacement);
        } else {
            fprintf(stderr, "%s write failed addr=0x%llx: %s\n", p->name, addr, strerror(errno));
        }
    }
    return 0;
}

static int patch_once(const char* pid, int verbose, const Options* options) {
    unsigned long long base = 0;
    if (find_jiagu_base(pid, &base) != 0) {
        if (verbose) fprintf(stderr, "libjiagu_vip.so base not found\n");
        return 1;
    }

    char mem_path[128];
    snprintf(mem_path, sizeof(mem_path), "/proc/%s/mem", pid);
    int memfd = open(mem_path, options->diag_only ? O_RDONLY : O_RDWR);
    if (memfd < 0) {
        fprintf(stderr, "open %s failed: %s\n", mem_path, strerror(errno));
        return 2;
    }

    int patched = 0;
    int ready = 0;
    int mismatched = 0;
    size_t count = sizeof(patches) / sizeof(patches[0]);
    if (!options->diag_only) {
        apply_patches(memfd, base, patches, count, verbose, &patched, &ready, &mismatched);
    } else {
        ready = (int)count;
    }
    if (!options->diag_only && options->force_register) {
        size_t extra_count = sizeof(force_register_patches) / sizeof(force_register_patches[0]);
        apply_patches(memfd, base, force_register_patches, extra_count, verbose, &patched, &ready, &mismatched);
        count += extra_count;
    }

    fprintf(stderr, "patch_once base=0x%llx patched=%d ready=%d mismatched=%d total=%zu\n",
            base, patched, ready, mismatched, count);
    if (ready == (int)count || verbose || options->diag_only) {
        print_jiagu_diag_samples(memfd, base, options);
    }
    close(memfd);
    return ready == (int)count ? 0 : 3;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <pid> [loops] [delay_ms] [force-register] [diag-only] [diag-samples=N] [diag-interval-ms=N]\n", argv[0]);
        return 2;
    }
    const char* pid = argv[1];
    int loops = argc >= 3 ? atoi(argv[2]) : 200;
    int delay_ms = argc >= 4 ? atoi(argv[3]) : 5;
    Options options = {0};
    options.diag_samples = 1;
    options.diag_interval_ms = 0;
    for (int i = 4; i < argc; ++i) {
        if (strcmp(argv[i], "force-register") == 0) {
            options.force_register = 1;
        } else if (strcmp(argv[i], "diag-only") == 0) {
            options.diag_only = 1;
        } else if (strncmp(argv[i], "diag-samples=", 13) == 0) {
            options.diag_samples = atoi(argv[i] + 13);
            if (options.diag_samples <= 0) options.diag_samples = 1;
        } else if (strncmp(argv[i], "diag-interval-ms=", 17) == 0) {
            options.diag_interval_ms = atoi(argv[i] + 17);
            if (options.diag_interval_ms < 0) options.diag_interval_ms = 0;
        } else {
            fprintf(stderr, "unknown option: %s\n", argv[i]);
            return 2;
        }
    }
    if (loops <= 0) loops = 1;
    if (delay_ms < 0) delay_ms = 0;

    int last_rc = 1;
    for (int i = 0; i < loops; ++i) {
        last_rc = patch_once(pid, i == loops - 1, &options);
        if (last_rc == 0) {
            fprintf(stderr, "success attempt=%d pid=%s\n", i, pid);
            return 0;
        }
        sleep_ms(delay_ms);
    }
    fprintf(stderr, "failed pid=%s rc=%d\n", pid, last_rc);
    return last_rc;
}
