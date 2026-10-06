// fake dlfcn.h — SOMENTE para o teste host do cache do loader (fixtures).
// Substitui o dlfcn real via -I fixtures/loader_cache/fake_dlfcn (primeiro no
// include path), de modo que o loader sob teste use os mocks sem conflito.
#pragma once

#define RTLD_NOW 2
#define RTLD_LOCAL 0

void * mock_dlopen(const char * name, int flags);
void * mock_dlsym(void * handle, const char * name);
char * mock_dlerror(void);
int    mock_dlclose(void * handle);

#define dlopen  mock_dlopen
#define dlsym   mock_dlsym
#define dlerror mock_dlerror
#define dlclose mock_dlclose
