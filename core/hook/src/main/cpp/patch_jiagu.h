#pragma once
#include "common.h"

// Patch 360 jiagu self-kill calls
bool patchJiaguSoIfPresent(const char* lib_path);

// Load and patch jiagu library
bool patchJiaguLoad(const char* lib_path);
