-- PR-3a — which line of the order each pick was taken for. The sale records a line's batches by this, not by product:
-- matching by product made two lines of the same product each record BOTH lines' batches (live: 2 sold, 4 recorded).
-- NULL on picks made before this column, and for callers that send no line reference (they match by product, as before).
ALTER TABLE reservation_picks ADD COLUMN line_ref INT NULL;
