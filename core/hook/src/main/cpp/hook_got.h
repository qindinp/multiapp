#pragma once
#include "common.h"

// PLT/GOT hooking functions
void* got_hook_library(const char* lib_name, const char* sym_name, void* new_func, void** orig_func);

// Original function pointers from GOT hooks
extern int (*got_orig_open)(const char*, int, ...);
extern FILE* (*got_orig_fopen)(const char*, const char*);
