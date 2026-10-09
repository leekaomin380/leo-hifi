#ifndef LEO_MOCK_H
#define LEO_MOCK_H
#include <stddef.h>
#include "cutils/properties.h"
#include "tinyalsa/asoundlib.h"
struct mixer *mock_mixer(void);
void mock_reset(void);
void mock_add_int(const char *name, int nvalues, int v0, int v1);
void mock_add_enum(const char *name, const char *val);
void mock_remove(const char *name);
void mock_set_int(const char *name, int v0, int v1);
void mock_set_enum(const char *name, const char *val);
void mock_fail_write(const char *name, int on);
void mock_skew_readback(const char *name, int skew);
void mock_enum_readback(const char *name, const char *val);
int  mock_get_int(const char *name, int idx);
const char *mock_get_enum(const char *name);
void mock_prop_put(const char *k, const char *v);
const char *mock_prop_get(const char *k);
extern int mock_property_set_fails;
extern int mock_write_count, mock_property_write_count;
extern int leo_log_count[6];
#endif
