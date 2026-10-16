-- Generates a log worth reading: 5 partitions and a spread of pruning outcomes.
-- Run with a clean data directory, createTable rejects a table that already exists:
--   ./scripts/gen-trips.sh > trips_big.csv
--   rm -rf data logs
--   ./engine -f scripts/workload.sql > /dev/null
-- Keep failing statements out of this file; the executor stops at the first error.

CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
COPY trips FROM 'trips_big.csv';

-- distance is sorted, so its partition ranges are tight and disjoint
SELECT * FROM trips WHERE distance > 4500;   -- reads 1 of 5
SELECT * FROM trips WHERE distance < 500;    -- reads 1 of 5
SELECT * FROM trips WHERE distance = 2500;   -- reads 1 of 5
SELECT * FROM trips WHERE distance > 0;      -- reads 5 of 5, nothing to prune

-- city is scattered, so every partition spans most of the alphabet
SELECT * FROM trips WHERE city = 'Odense';   -- reads 5 of 5
