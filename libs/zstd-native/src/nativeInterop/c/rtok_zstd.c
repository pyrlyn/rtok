/* Streaming decode of every frame (Rust zstd::decode_all), so frames without a content size work too. */
#include <stdlib.h>
#include <string.h>
#include "zstd.h"
#include "rtok_zstd.h"

int rtok_zstd_decode_all(const void *src, size_t src_len, size_t cap, void **out, size_t *out_len, const char **err) {
    ZSTD_DStream *ds = ZSTD_createDStream();
    if (!ds) { *err = "zstd: out of memory"; return -1; }
    size_t size = src_len * 4 + 4096, used = 0;
    if (size > cap) size = cap;
    unsigned char *buf = malloc(size ? size : 1);
    if (!buf) { ZSTD_freeDStream(ds); *err = "zstd: out of memory"; return -1; }
    ZSTD_inBuffer in = { src, src_len, 0 };
    size_t ret = 0; /* stays 0 for empty input: nothing to drain */
    while (in.pos < in.size) {
        if (used == size) {
            if (size >= cap) { free(buf); ZSTD_freeDStream(ds); *err = "zstd: output over the size cap"; return -1; }
            size_t next = size * 2 > cap ? cap : size * 2;
            unsigned char *grown = realloc(buf, next);
            if (!grown) { free(buf); ZSTD_freeDStream(ds); *err = "zstd: out of memory"; return -1; }
            buf = grown; size = next;
        }
        ZSTD_outBuffer o = { buf + used, size - used, 0 };
        ret = ZSTD_decompressStream(ds, &o, &in);
        if (ZSTD_isError(ret)) { *err = ZSTD_getErrorName(ret); free(buf); ZSTD_freeDStream(ds); return -1; }
        used += o.pos;
        if (o.pos == 0 && in.pos == in.size) break;
    }
    /* Drain what the decoder still holds once all input is consumed. */
    while (ret != 0) {
        if (used == size) {
            if (size >= cap) { free(buf); ZSTD_freeDStream(ds); *err = "zstd: output over the size cap"; return -1; }
            size_t next = size * 2 > cap ? cap : size * 2;
            unsigned char *grown = realloc(buf, next);
            if (!grown) { free(buf); ZSTD_freeDStream(ds); *err = "zstd: out of memory"; return -1; }
            buf = grown; size = next;
        }
        ZSTD_outBuffer o = { buf + used, size - used, 0 };
        ret = ZSTD_decompressStream(ds, &o, &in);
        if (ZSTD_isError(ret)) { *err = ZSTD_getErrorName(ret); free(buf); ZSTD_freeDStream(ds); return -1; }
        used += o.pos;
        if (o.pos == 0) { if (ret != 0) { free(buf); ZSTD_freeDStream(ds); *err = "zstd: truncated frame"; return -1; } }
    }
    ZSTD_freeDStream(ds);
    *out = buf; *out_len = used;
    return 0;
}

void rtok_zstd_free(void *p) { free(p); }
