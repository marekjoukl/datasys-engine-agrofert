# Experiment results

Latitude 5320, commit `12e77e9`. Conditions, every run and the summary are in [results/latitude-5320](results/latitude-5320).

Partitions read and median `queryMs` of runs 2 to 6:

| maxRowsPerPartition | read, sorted | read, shuffled | ms, sorted | ms, shuffled |
|---|---|---|---|---|
| 8 | 10.0% | 57.3% | 21,194 | 114,504 |
| 32 | 10.0% | 96.7% | 1,522 | 12,828 |
| 128 | 10.1% | 100.0% | 305 | 1,038 |
| 512 | 10.7% | 100.0% | 159 | 272 |
| 1024 | 11.2% | 100.0% | 134 | 200 |
| 2048 | 12.2% | 100.0% | 124 | 172 |
| 8192 | 23.1% | 100.0% | 115 | 140 |
| 32768 | 50.0% | 100.0% | 117 | 134 |

![Figure 1: partitions read](results/latitude-5320/figure-1-partitions-read.png)

![Figure 2: query time](results/latitude-5320/figure-2-query-time.png)
