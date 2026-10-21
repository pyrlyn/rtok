T356 stops the graph from indexing `$HOME`. `worktree_list` and a pathless `search` still ran against the launch cwd, so a server started in the home directory failed with git's `fatal: not a git repository` or `no project root` until the client sent `roots/list` or the caller passed a path.

`project_dir` uses the launch cwd when it lies in a checkout, otherwise the first client root that does. `worktree_list` and a pathless `search` from `/` or `$HOME` use that directory. With no such root the error says to pass a path or open a project. `walk_root_ok` uses the same wording.

Check: an MCP test with cwd = a non-repo and a `roots/list` root; the no-root error text; `just check`.

Check result: `project_dir_prefers_cwd_then_extra_and_never_git_fatal` checks cwd, then a client root, then the refusal. `mcp_home_cwd_worktree_list_and_search_use_the_client_root` launches in `/` with a first root of `/` and a second root inside a repository: `worktree_list` names that checkout and its linked worktree, and a pathless `search` finds `needle.txt`. `mcp_home_cwd_without_a_root_says_pass_a_path_or_open_a_project` refuses both tools with `no project root` and `pass a path or open a project`, and the text does not contain `fatal:`.

Status: done 2026-10-19
