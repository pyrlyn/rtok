#ifndef RTOK_ZSTD_H
#define RTOK_ZSTD_H
#include <stddef.h>
/* Decodes every frame of src into a malloc'd buffer (*out, *out_len); at most cap bytes.
   0 on success, else -1 with a static message in *err. Free *out with rtok_zstd_free. */
int rtok_zstd_decode_all(const void *src, size_t src_len, size_t cap, void **out, size_t *out_len, const char **err);
void rtok_zstd_free(void *p);
#endif
