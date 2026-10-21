An index of a supported file that could not be read stored a stat the next run trusted, so restoring read permission without changing bytes, size, or mtime left the file empty. An unreadable file is now staged with stat `(0, 0)`, which the stat gate never treats as unchanged, so the next index reads it again.

Landed in `845af7ed` (#1054). This card only records that the check already holds on main.

Check: a file that becomes readable is re-parsed even when mtime and size are unchanged.

Check result: `a_file_unreadable_at_index_time_is_read_once_access_returns` indexes `locked.rs` at mode `000`, restores `0644` without moving mtime or size, and the second index parses `hidden_until_readable`.

Status: done 2026-10-19
