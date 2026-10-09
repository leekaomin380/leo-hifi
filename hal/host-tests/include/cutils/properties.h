#ifndef HOSTMOCK_PROPERTIES_H
#define HOSTMOCK_PROPERTIES_H
#define PROPERTY_VALUE_MAX 92
#define PROPERTY_KEY_MAX   92
int property_get(const char *key, char *value, const char *default_value);
int property_set(const char *key, const char *value);
#endif
