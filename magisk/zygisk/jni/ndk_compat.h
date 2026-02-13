// NDK r25+ Compatibility Header
// This must be included first in all C++ files to fix type definition issues

#ifndef TWINAUDIO_NDK_COMPAT_H
#define TWINAUDIO_NDK_COMPAT_H

// Define basic integer types using compiler builtins BEFORE including any headers
// This avoids circular dependency issues with pthread_types.h
#ifndef _INT8_T_DECLARED
typedef __INT8_TYPE__ int8_t;
#define _INT8_T_DECLARED
#endif

#ifndef _INT16_T_DECLARED
typedef __INT16_TYPE__ int16_t;
#define _INT16_T_DECLARED
#endif

#ifndef _INT32_T_DECLARED
typedef __INT32_TYPE__ int32_t;
#define _INT32_T_DECLARED
#endif

#ifndef _INT64_T_DECLARED
typedef __INT64_TYPE__ int64_t;
#define _INT64_T_DECLARED
#endif

#ifndef _UINT8_T_DECLARED
typedef __UINT8_TYPE__ uint8_t;
#define _UINT8_T_DECLARED
#endif

#ifndef _UINT16_T_DECLARED
typedef __UINT16_TYPE__ uint16_t;
#define _UINT16_T_DECLARED
#endif

#ifndef _UINT32_T_DECLARED
typedef __UINT32_TYPE__ uint32_t;
#define _UINT32_T_DECLARED
#endif

#ifndef _UINT64_T_DECLARED
typedef __UINT64_TYPE__ uint64_t;
#define _UINT64_T_DECLARED
#endif

#ifndef _SIZE_T_DECLARED
typedef __SIZE_TYPE__ size_t;
#define _SIZE_T_DECLARED
#endif

#ifndef _PTRDIFF_T_DECLARED
typedef __PTRDIFF_TYPE__ ptrdiff_t;
#define _PTRDIFF_T_DECLARED
#endif

// Now include the real headers to get additional definitions
#include <stdint.h>
#include <stddef.h>

#endif // TWINAUDIO_NDK_COMPAT_H





