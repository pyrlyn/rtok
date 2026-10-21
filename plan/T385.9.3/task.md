---
status: todo
priority: P3
complexity: 2
readiness: 40%
agent:
---

# T385.9.3. Gate P28 live bench for the compressor

Part of T385.9, after T385.9.2. `rtok bench` cost per passed task with the compressor on against the v0.1 lossless path, compressor tokens included, plus the must-keep fixture and `expand` recovery; one dated `research.md` row. Needs the creator's session and API spend (like T394), so an agent cannot run it alone. Green → T385.9 closes and the flag may become a default in a follow-up; red → the flag stays off with its number.

Check: the dated `research.md` row with cost per passed task, pass rate and compressor tokens.

Progress (2026-10-21): the bench is built and unrun. `rtok bench --suite p28` (`bench/p28.toml`, arm proxies and settings in `bench/p28/`, how to run it in `bench/p28/README.md`) runs six tasks on two arms that differ only in `[plugins.compress.llm] enabled`. Each task reads the seven must-keep fixture bodies, then asks for a span the pointer hides. Per arm it prints pass rate, agent USD, the compressor's `internal`-lane tokens and USD from the arm proxy's store, cost per passed task, and how many stored summaries keep every fixture span and still `expand`. Left: the live run with the creator's API spend (T394) and its `research.md` row.

Progress (2026-10-15, `research.md` §16.9): a local attempt on LM Studio (`prism-ml/bonsai-27b`) gave no evidence: the control arm returned an empty answer, and with the flag on the compressor never fired (0 summaries). Left: a bench that can pass its control (a capable model), tasks with more than `keep_turns` turns after the large results, and a compressor arm in `rtok bench`; the paid run still needs the creator.

Progress (2026-10-21, `research.md` §16.11): the arm and the tasks are in. `bench/p28/` holds an `rtok bench` overlay with arms `a` (:8811, compress mode, compressor off) and `b` (:8812, plus `RTOK_PLUGINS_COMPRESS_LLM_ENABLED=true`). It has six tasks that read eight 13 KB logs one per turn and then answer from one of the first three, with checks that can fail. Tests pin the arms and show that each answer is a must-keep span that neither the pointer nor the extractive view shows. Nothing ran. Left: the paid run by the §16.11 protocol (T394), with the compressor's internal-lane cost added to arm `b` by hand.
