-- T329.36: what a graph call returned, counted by the backends while they built the answer
-- and written on the end event only: asked symbols the answer lists, distinct files of its
-- rows, and projects of the scope that held a row. NULL for a call that does not count
-- (`explore`, `outline`, a failed call) and for rows written before these columns existed.
ALTER TABLE graph_events ADD COLUMN symbols_returned INTEGER;
ALTER TABLE graph_events ADD COLUMN files_touched INTEGER;
ALTER TABLE graph_events ADD COLUMN projects_hit INTEGER;
