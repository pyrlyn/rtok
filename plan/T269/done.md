Header lines were read with `read_line`. One invalid UTF-8 byte returned `Err`, and `read_frame` treated that as EOF, so the header and every following frame were dropped. Header lines are read as bytes (`read_until`). A line that is not UTF-8 is kept and skipped for `Content-Length` parsing; the next frame is still read.

Check: `invalid_utf8_in_a_header_is_forwarded_not_eof`; the existing framing tests stay green.
