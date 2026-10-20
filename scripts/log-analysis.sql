-- The engine reading its own log. Prerequisites:
--   uv run scripts/experiment.py generate > trips_big.csv
--   rm -rf data logs
--   ./engine -f scripts/workload.sql > /dev/null
--   ./engine -c "SELECT * FROM nosuchtable"       -- leaves the ERROR line
--   cp logs/engine.log logs/snapshot.log
--   ./engine -f scripts/log-analysis.sql 2>/dev/null
-- The snapshot matters: without it the COPY appends to the file it is reading.

CREATE TABLE logs (timestamp STRING, sessionId STRING, statementNumber LONG, threadId LONG,
                   logLevel STRING, className STRING, logMessage STRING);
COPY logs FROM 'logs/snapshot.log';

-- One session end to end. Replace the id with one from your own snapshot.
SELECT * FROM logs WHERE sessionId = '2bf63df7-12c7-40aa-8389-73c6e28ab7d4';

-- One statement's whole story: the last SELECT of the workload, city = 'Odense'.
SELECT * FROM logs WHERE statementNumber = 7;

-- Every failure in the file.
SELECT * FROM logs WHERE logLevel = 'ERROR';

-- statementNumber alone spans sessions: statement 1 is the workload's CREATE TABLE
-- and the failing SELECT of the next run. Only sessionId separates them.
SELECT * FROM logs WHERE statementNumber = 1;
