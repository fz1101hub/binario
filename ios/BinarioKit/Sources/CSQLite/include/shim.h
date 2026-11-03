#ifndef BINARIO_CSQLITE_SHIM_H
#define BINARIO_CSQLITE_SHIM_H

#include <sqlite3.h>

static inline sqlite3_destructor_type binario_sqlite_transient(void) {
    return SQLITE_TRANSIENT;
}

#endif
