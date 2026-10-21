The compressor speaks Anthropic Messages. It used to forward whatever `Authorization` the agent request carried, so an OpenAI bearer could be sent with that body. `Auth::from_headers` now returns a credential only when the request's wire provider is `anthropic`. An OpenAI or Gemini turn offers nothing, and the summary is skipped rather than authenticated as the wrong provider.

Landed in `9b33846e` (#1052). This card records that the check already holds on main.

Check: an OpenAI bearer is never presented as an Anthropic credential by default; valid configured internal provider requests continue to work.

Check result: `an_openai_credential_is_never_sent_to_the_anthropic_compressor` sends three OpenAI turns with `Bearer sk-openai-test` and the fake compressor sees zero calls. `an_anthropic_bearer_credential_still_reaches_the_compressor` still delivers `Bearer oauth-test` to `/v1/messages` and stores the summary.

Status: done 2026-10-19
