#ifndef CINT_TREE_SITTER_GRAMMAR_DL_H_
#define CINT_TREE_SITTER_GRAMMAR_DL_H_

#ifdef __cplusplus
extern "C" {
#endif

void *dlopen(const char *filename, int flags);
void *dlsym(void *handle, const char *symbol);
int dlclose(void *handle);
char *dlerror(void);

#ifdef __cplusplus
}
#endif

#endif
