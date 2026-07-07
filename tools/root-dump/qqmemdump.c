#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

static int contains_any(const char* text, int count, char** needles) {
    if (text == NULL) return 0;
    for (int i = 0; i < count; ++i) {
        if (needles[i] != NULL && needles[i][0] != '\0' && strstr(text, needles[i]) != NULL) {
            return 1;
        }
    }
    return 0;
}

static void sanitize(char* s) {
    for (; *s; ++s) {
        unsigned char c = (unsigned char)*s;
        if (!isalnum(c) && c != '.' && c != '-' && c != '_') {
            *s = '_';
        }
    }
}

static int mkdir_p(const char* path) {
    char tmp[1024];
    size_t len = strlen(path);
    if (len == 0 || len >= sizeof(tmp)) return -1;
    memcpy(tmp, path, len + 1);
    for (char* p = tmp + 1; *p; ++p) {
        if (*p == '/') {
            *p = '\0';
            if (mkdir(tmp, 0755) != 0 && errno != EEXIST) return -1;
            *p = '/';
        }
    }
    if (mkdir(tmp, 0755) != 0 && errno != EEXIST) return -1;
    return 0;
}

static int copy_range(int memfd, unsigned long long start, unsigned long long end, const char* out_path) {
    int out = open(out_path, O_CREAT | O_TRUNC | O_WRONLY, 0644);
    if (out < 0) {
        fprintf(stderr, "open out failed %s: %s\n", out_path, strerror(errno));
        return -1;
    }

    unsigned char buf[65536];
    unsigned long long pos = start;
    while (pos < end) {
        size_t want = sizeof(buf);
        if (end - pos < want) want = (size_t)(end - pos);
        ssize_t n = pread(memfd, buf, want, (off_t)pos);
        if (n <= 0) {
            fprintf(stderr, "pread failed at 0x%llx len=%zu: %s\n", pos, want, strerror(errno));
            close(out);
            return -1;
        }
        ssize_t written = 0;
        while (written < n) {
            ssize_t w = write(out, buf + written, (size_t)(n - written));
            if (w <= 0) {
                fprintf(stderr, "write failed %s: %s\n", out_path, strerror(errno));
                close(out);
                return -1;
            }
            written += w;
        }
        pos += (unsigned long long)n;
    }

    close(out);
    return 0;
}

int main(int argc, char** argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <pid> <out_dir> [--require <needle>] <match> [match...]\n", argv[0]);
        return 2;
    }

    const char* pid = argv[1];
    const char* out_dir = argv[2];
    const char* require = NULL;
    int match_arg = 3;
    if (argc >= 6 && strcmp(argv[3], "--require") == 0) {
        require = argv[4];
        match_arg = 5;
    }
    if (match_arg >= argc) {
        fprintf(stderr, "no match patterns provided\n");
        return 2;
    }
    if (mkdir_p(out_dir) != 0) {
        fprintf(stderr, "mkdir failed %s: %s\n", out_dir, strerror(errno));
        return 1;
    }

    char maps_path[128];
    char mem_path[128];
    snprintf(maps_path, sizeof(maps_path), "/proc/%s/maps", pid);
    snprintf(mem_path, sizeof(mem_path), "/proc/%s/mem", pid);

    FILE* maps = fopen(maps_path, "r");
    if (maps == NULL) {
        fprintf(stderr, "open maps failed %s: %s\n", maps_path, strerror(errno));
        return 1;
    }

    if (require != NULL && require[0] != '\0') {
        int found = 0;
        char require_line[4096];
        while (fgets(require_line, sizeof(require_line), maps) != NULL) {
            if (strstr(require_line, require) != NULL) {
                found = 1;
                break;
            }
        }
        fclose(maps);
        if (!found) {
            fprintf(stderr, "required pattern not present: %s\n", require);
            return 3;
        }
        maps = fopen(maps_path, "r");
        if (maps == NULL) {
            fprintf(stderr, "reopen maps failed %s: %s\n", maps_path, strerror(errno));
            return 1;
        }
    }

    int memfd = open(mem_path, O_RDONLY);
    if (memfd < 0) {
        fprintf(stderr, "open mem failed %s: %s\n", mem_path, strerror(errno));
        fclose(maps);
        return 1;
    }

    char maps_out_path[1200];
    snprintf(maps_out_path, sizeof(maps_out_path), "%s/maps.txt", out_dir);
    FILE* maps_out = fopen(maps_out_path, "w");
    if (maps_out == NULL) {
        fprintf(stderr, "open maps out failed %s: %s\n", maps_out_path, strerror(errno));
        close(memfd);
        fclose(maps);
        return 1;
    }

    char line[4096];
    int index = 0;
    int dumped = 0;
    while (fgets(line, sizeof(line), maps) != NULL) {
        fputs(line, maps_out);

        unsigned long long start = 0;
        unsigned long long end = 0;
        unsigned long long offset = 0;
        char perms[8] = {0};
        char dev[32] = {0};
        unsigned long inode = 0;
        char path[2048] = {0};
        int fields = sscanf(line, "%llx-%llx %7s %llx %31s %lu %2047[^\n]",
                            &start, &end, perms, &offset, dev, &inode, path);
        if (fields < 6 || start >= end || perms[0] != 'r') {
            continue;
        }
        if (fields < 7) {
            path[0] = '\0';
        }
        if (!contains_any(path, argc - match_arg, argv + match_arg)) {
            continue;
        }

        char clean[512];
        const char* label = path[0] ? path : "anonymous";
        snprintf(clean, sizeof(clean), "%s", label);
        sanitize(clean);

        char out_path[1600];
        snprintf(out_path, sizeof(out_path),
                 "%s/range-%02d-%016llx-%016llx-off_%llx-%s-%s.bin",
                 out_dir, index, start, end, offset, perms, clean);
        ++index;

        if (copy_range(memfd, start, end, out_path) == 0) {
            ++dumped;
            fprintf(stderr, "dumped %s\n", out_path);
        }
    }

    fclose(maps_out);
    close(memfd);
    fclose(maps);
    fprintf(stderr, "done dumped=%d\n", dumped);
    return dumped > 0 ? 0 : 3;
}
