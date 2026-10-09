#ifndef HOSTMOCK_TINYALSA_H
#define HOSTMOCK_TINYALSA_H
#include <stddef.h>
struct mixer;
struct mixer_ctl;
struct mixer_ctl *mixer_get_ctl_by_name(struct mixer *mixer, const char *name);
int   mixer_ctl_get_value(struct mixer_ctl *ctl, unsigned int id);
const char *mixer_ctl_get_enum_string(struct mixer_ctl *ctl, unsigned int enum_id);
int   mixer_ctl_set_array(struct mixer_ctl *ctl, const void *array, size_t count);
int   mixer_ctl_set_enum_by_string(struct mixer_ctl *ctl, const char *string);
unsigned int mixer_ctl_get_num_values(struct mixer_ctl *ctl);
#endif
