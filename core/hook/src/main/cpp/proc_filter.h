#pragma once
#include "common.h"

// Create filtered version of /proc/self/maps
FILE* create_filtered_maps_file();

// Spoof /proc/self files
void spoof_proc_self_cmdline();
void spoof_proc_self_comm();
void spoof_proc_self_status();
