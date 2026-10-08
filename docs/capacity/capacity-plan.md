# Capacity and cost plan

All numbers below are assumptions/estimates; no measured result yet.

Assume10M registered,3M MAU,1M DAU,200k concurrent online,100k concurrent viewers baseline,1M live peak. Average viewing session30min and mean delivered bitrate4Mbps; heartbeat30s; progress30s. API10 calls/online user/min baseline. Peak starts1000/s steady,10k/s flash (scheduled model). Each start3 owner checks,1 lease acquire+1 durable write; heartbeat does not call optional services.

| Workload | Formula | Estimated baseline / peak |
|---|---|---|
| Playback starts | viewers/session1800s (steady) | 56/s baseline;556/s million viewers; burst assumptions1000/10k |
| API RPS | online×10/60 | 33,333 baseline; live peak user-action dependent |
| Progress writes | viewers/30 | 3,333/s /33,333/s |
| Redis QPS | heartbeat viewers/30 + acquire + cache checks3×starts | ~7,333/s using1000starts /73,333/s peak |
| PostgreSQL writes | progress + starts(session/order subset) | ≥4,333/s /43,333/s; split ownership and batch async history |
| Kafka events | progress +2×starts +QoE viewers/60 | ~7,000/s /70,000/s plus ads/search |
| Kafka bytes | events×512B×86400 | ~310GB/day baseline, replication3≈930GB/day before compression |
| Search RPS | DAU×8 queries/day/86400×peakfactor10 | ~926/s; peak factor assumption |
| CDN egress | viewers×4Mbps | 400Gbps baseline /4Tbps peak |
| CDN daily bytes | mean concurrency100k×4Mbps×86400/8 | 4.32PB/day if sustained; actual viewing duty cycle must replace this pessimistic estimate |
| Media storage | 100k titles×2h×sum ladder12Mbps/8 | 1.08PB packaged +source+replica overhead |

Control plane never receives CDN bytes. Network peak formulas are not benchmark results. Measure before sizing instance counts; required replica count=ceil(peakRPS/measured safe RPS per replica×headroom1.3), then constrain DB pools. Kafka partitions≥peak consumed rate/measured partition throughput, with hotspot analysis by account/profile. Live content hot keys use immutable local cache+singleflight. Owner writes must shard only after measured saturation and ADR.

Cost drivers: CDN egress dominates; use negotiated CDN rates and origin shielding. S3 storage variants/codecs vs encoding cost; lifecycle unused variants; cap logs/trace sampling and retention; reserved baseline+scheduled scale for live; avoid always-on large OpenSearch/Temporal before their phases. Cost worksheet requires regional unit prices and actual consumption; no dollar estimate without verified prices. Monthly cost per streamed hour and per successful start; production budget alert at80/100% configured allowance.
