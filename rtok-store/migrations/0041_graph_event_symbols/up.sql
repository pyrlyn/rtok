-- T329.33: how many symbols a graph call asked for (`names`, `name`, `id`), written on every
-- event of the call. The answer's size is not a stand-in: a name the index does not know
-- still counts as asked. NULL for a tool that takes no symbol (`outline`, `explore`) and
-- for rows written before this column existed.
ALTER TABLE graph_events ADD COLUMN symbols INTEGER;
