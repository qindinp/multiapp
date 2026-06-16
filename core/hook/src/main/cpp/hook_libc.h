#pragma once
#include "common.h"

// Install libc inline hooks via shadowhook
void install_libc_hooks();

// Uninstall all libc hooks
void uninstall_libc_hooks();

// Original function pointers (extern declarations)
extern int (*real_open)(const char*, int, ...);
extern FILE* (*real_fopen)(const char*, const char*);
extern int (*real_access)(const char*, int);
extern int (*real_stat)(const char*, struct stat*);
extern ssize_t (*real_readlink)(const char*, char*, size_t);
