Promoted from I-111 (Ivan, 2026-10-04). From `research.md` §16.3 #10: a local model drafts output and the cloud model verifies it, cutting cloud output tokens, which dominate cost on Fable (§2).

Done means: a research pass first — which hosts and APIs allow a pre-filled assistant draft, which local models are fast enough on Apple Silicon, how verification is prompted — recorded in `research.md` with primary sources. Build only if the bench shows cost per passed task falls with the pass rate held; otherwise close with the finding.

Check: the dated `research.md` section; the go/no-go recorded in this card's done entry.

Result: closed with the finding, nothing built (`research.md` §38, sources checked 2026-10-11). Claude 4.6+ rejects a prefilled assistant message; OpenAI Predicted Outputs is unsupported with `tools`; 96 % of output is tool-call input, which a text draft cannot carry; the break-even needs an accept rate above 0.2 plus the verdict cost. Reopen only if a bench can show cost per passed task falling with the pass rate held. Not covered: Claude Code and Codex request paths, Ollama, any measured local-model pass rate.
