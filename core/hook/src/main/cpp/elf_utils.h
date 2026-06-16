#pragma once
#include "common.h"

// Byte pattern search
int find_bytes(const uint8_t* data, size_t data_len, const uint8_t* pattern, size_t pattern_len);

// Read values from byte buffer
int32_t read_int_le(const uint8_t* data, size_t offset);
int64_t read_long_le(const uint8_t* data, size_t offset);
int16_t read_short_le(const uint8_t* data, size_t offset);
