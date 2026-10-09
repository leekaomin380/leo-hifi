/* Host-side mixer/property mock for the Leo HiFi controller fault harness. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "mock.h"

int leo_log_count[6];
int mock_write_count, mock_property_write_count;

#define MAX_CTL 96
#define MAX_PROP 16

struct mock_ctl {
    char  name[64];
    int   present;
    int   is_enum;
    int   nvalues;
    int   v[2];             /* integer values                     */
    char  ev[32];           /* enum string value                  */
    int   write_fails;      /* set_* returns -EIO                 */
    int   readback_skew;    /* value read back differs by this    */
    char  ev_readback[32];  /* if non-empty, enum read-back value */
};

static struct mock_ctl g_ctl[MAX_CTL];
static int g_nctl;

static struct { char k[96], v[96]; int present; } g_prop[MAX_PROP];
int mock_property_set_fails;

struct mixer *mock_mixer(void) { return (struct mixer *)(void *)&g_ctl; }

void mock_reset(void)
{
    memset(g_ctl, 0, sizeof(g_ctl));
    memset(g_prop, 0, sizeof(g_prop));
    memset(leo_log_count, 0, sizeof(leo_log_count));
    g_nctl = 0;
    mock_property_set_fails = 0;
}

static struct mock_ctl *find(const char *name)
{
    int i;
    for (i = 0; i < g_nctl; i++)
        if (strcmp(g_ctl[i].name, name) == 0)
            return &g_ctl[i];
    return NULL;
}

void mock_add_int(const char *name, int nvalues, int v0, int v1)
{
    struct mock_ctl *c = &g_ctl[g_nctl++];
    memset(c, 0, sizeof(*c));
    snprintf(c->name, sizeof(c->name), "%s", name);
    c->present = 1; c->is_enum = 0; c->nvalues = nvalues;
    c->v[0] = v0; c->v[1] = v1;
}

void mock_add_enum(const char *name, const char *val)
{
    struct mock_ctl *c = &g_ctl[g_nctl++];
    memset(c, 0, sizeof(*c));
    snprintf(c->name, sizeof(c->name), "%s", name);
    c->present = 1; c->is_enum = 1; c->nvalues = 1;
    snprintf(c->ev, sizeof(c->ev), "%s", val);
}

void mock_remove(const char *name)
{
    struct mock_ctl *c = find(name);
    if (c) c->present = 0;
}

void mock_set_int(const char *name, int v0, int v1)
{
    struct mock_ctl *c = find(name);
    if (c) { c->v[0] = v0; c->v[1] = v1; }
}

void mock_set_enum(const char *name, const char *val)
{
    struct mock_ctl *c = find(name);
    if (c) snprintf(c->ev, sizeof(c->ev), "%s", val);
}

void mock_fail_write(const char *name, int on)
{
    struct mock_ctl *c = find(name);
    if (c) c->write_fails = on;
}

void mock_skew_readback(const char *name, int skew)
{
    struct mock_ctl *c = find(name);
    if (c) c->readback_skew = skew;
}

void mock_enum_readback(const char *name, const char *val)
{
    struct mock_ctl *c = find(name);
    if (c) snprintf(c->ev_readback, sizeof(c->ev_readback), "%s", val);
}

int mock_get_int(const char *name, int idx)
{
    struct mock_ctl *c = find(name);
    return (c && c->present) ? c->v[idx] : -1;
}

const char *mock_get_enum(const char *name)
{
    struct mock_ctl *c = find(name);
    return (c && c->present) ? c->ev : NULL;
}

/* ---- tinyalsa surface ---- */
struct mixer_ctl *mixer_get_ctl_by_name(struct mixer *mixer, const char *name)
{
    struct mock_ctl *c;
    (void)mixer;
    c = find(name);
    if (c == NULL || !c->present)
        return NULL;
    return (struct mixer_ctl *)c;
}

int mixer_ctl_get_value(struct mixer_ctl *ctl, unsigned int id)
{
    struct mock_ctl *c = (struct mock_ctl *)ctl;
    if (c == NULL || !c->present) return -22;
    if (c->is_enum) return 0;               /* enum index is always 0 here */
    if (id > 1) return -22;
    return c->v[id] + c->readback_skew;
}

const char *mixer_ctl_get_enum_string(struct mixer_ctl *ctl, unsigned int enum_id)
{
    struct mock_ctl *c = (struct mock_ctl *)ctl;
    (void)enum_id;
    if (c == NULL || !c->present) return NULL;
    if (c->ev_readback[0] != '\0') return c->ev_readback;
    return c->ev;
}

int mixer_ctl_set_array(struct mixer_ctl *ctl, const void *array, size_t count)
{
    mock_write_count++;
    struct mock_ctl *c = (struct mock_ctl *)ctl;
    const int *a = (const int *)array;
    size_t i;
    if (c == NULL || !c->present) return -22;
    if (c->write_fails) return -5;
    for (i = 0; i < count && i < 2; i++)
        c->v[i] = a[i];
    return 0;
}

int mixer_ctl_set_enum_by_string(struct mixer_ctl *ctl, const char *string)
{
    mock_write_count++;
    struct mock_ctl *c = (struct mock_ctl *)ctl;
    if (c == NULL || !c->present) return -22;
    if (c->write_fails) return -5;
    snprintf(c->ev, sizeof(c->ev), "%s", string);
    return 0;
}

unsigned int mixer_ctl_get_num_values(struct mixer_ctl *ctl)
{
    struct mock_ctl *c = (struct mock_ctl *)ctl;
    return (c && c->present) ? (unsigned int)c->nvalues : 0u;
}

/* ---- property surface ---- */
int property_get(const char *key, char *value, const char *default_value)
{
    int i;
    for (i = 0; i < MAX_PROP; i++) {
        if (g_prop[i].present && strcmp(g_prop[i].k, key) == 0) {
            snprintf(value, PROPERTY_VALUE_MAX, "%s", g_prop[i].v);
            return (int)strlen(value);
        }
    }
    if (default_value) {
        snprintf(value, PROPERTY_VALUE_MAX, "%s", default_value);
        return (int)strlen(value);
    }
    value[0] = '\0';
    return 0;
}

int property_set(const char *key, const char *value)
{
    mock_property_write_count++;
    int i;
    if (mock_property_set_fails)
        return -1;                       /* SELinux denial before property_contexts */
    for (i = 0; i < MAX_PROP; i++) {
        if (g_prop[i].present && strcmp(g_prop[i].k, key) == 0) {
            snprintf(g_prop[i].v, sizeof(g_prop[i].v), "%s", value);
            return 0;
        }
    }
    for (i = 0; i < MAX_PROP; i++) {
        if (!g_prop[i].present) {
            g_prop[i].present = 1;
            snprintf(g_prop[i].k, sizeof(g_prop[i].k), "%s", key);
            snprintf(g_prop[i].v, sizeof(g_prop[i].v), "%s", value);
            return 0;
        }
    }
    return -1;
}

void mock_prop_put(const char *k, const char *v) { property_set(k, v); }
const char *mock_prop_get(const char *k)
{
    static char buf[96];
    property_get(k, buf, "");
    return buf;
}
