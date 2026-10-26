// Glue between the vendored tree-sitter runtime/grammars and Kotlin/Native (cinterop).
#ifndef RTOK_TS_H
#define RTOK_TS_H
#include "tree_sitter/api.h"
typedef struct {
  const char *name;                 // grammar name from grammar.json, e.g. "rust", "tsx"
  const TSLanguage *(*language)(void);
  const char *tags;                 // queries/tags.scm or NULL
  const char *locals;               // queries/locals.scm or NULL
  const char *highlights;           // queries/highlights.scm or NULL
} RtokGrammar;
extern const RtokGrammar rtok_ts_grammars[];
extern const int rtok_ts_grammar_count;
#endif
