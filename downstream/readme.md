
> Base URL: `http://localhost:8000`  
> Mutations can randomly return **503** (chaos). If that happens, just re-run the same call.  
> Adjacency is enforced by default (moves must be to the next city in `/route`).

---

## Route & Waypoints

### Ordered route (idx, city, handle, id)
```bash
curl -s http://localhost:8000/route | jq

All waypoints + occupancy
curl -s http://localhost:8000/waypoints | jq

Waypoint by city name (URL-encode spaces if using path)
curl -s "http://localhost:8000/waypoints/city/Ho%20Chi%20Minh%20City" | jq

Shipments
Create shipment with fixed handle (re-run may 409 if exists)
curl -s -X POST http://localhost:8000/shipments \
  -H 'Content-Type: application/json' \
  -d '{"handle":"shipment-demo-1"}' | jq

Create shipment with unique handle (safe to repeat)
curl -s -X POST http://localhost:8000/shipments \
  -H 'Content-Type: application/json' \
  -d "$(jq -n --arg h "shipment-$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM" '{handle:$h}')" | jq

Lookup shipment by handle (id & current index)
curl -s "http://localhost:8000/shipments?handle=shipment-demo-1" | jq

Get shipment by id (inline handle→id)
curl -s "http://localhost:8000/shipments/$(curl -s 'http://localhost:8000/shipments?handle=shipment-demo-1' | jq -r '.shipment.id')" | jq

Move (by city names)

These compute shipment_id, current index, and next cities at call time.

Move one hop from current city → next city
curl -s -X POST http://localhost:8000/move \
  -H 'Content-Type: application/json' \
  -d "$(jq -n \
        --argjson sid "$(curl -s 'http://localhost:8000/shipments?handle=shipment-demo-1' | jq '.shipment.id')" \
        --arg from "$(curl -s http://localhost:8000/route | jq -r --argjson i "$(curl -s "http://localhost:8000/shipments/$(curl -s 'http://localhost:8000/shipments?handle=shipment-demo-1' | jq '.shipment.id')" | jq '.shipment.current_idx')" '.order[$i].city')" \
        --arg to   "$(curl -s http://localhost:8000/route | jq -r --argjson i "$(curl -s "http://localhost:8000/shipments/$(curl -s 'http://localhost:8000/shipments?handle=shipment-demo-1' | jq '.shipment.id')" | jq '.shipment.current_idx')" '.order[$i+1].city')" \
        '{shipment_id:$sid, from:$from, to:$to}')" | jq

Manual city names (explicit strings)

Only works if the named cities are adjacent in your current /route.

# Move "shipment-demo-1" from "Honolulu" to "Tokyo"
curl -s -X POST http://localhost:8000/move \
  -H 'Content-Type: application/json' \
  -d "$(jq -n \
        --argjson sid "$(curl -s 'http://localhost:8000/shipments?handle=shipment-demo-1' | jq '.shipment.id')" \
        --arg from 'Honolulu' \
        --arg to   'Tokyo' \
        '{shipment_id:$sid, from:$from, to:$to}')" | jq


Tip: run curl -s http://localhost:8000/route | jq -r '.order[] | "\(.idx) \(.city)"' to see the current order.

Reset to origin (no locks)
Simplest reset (from current city)
curl -s -X POST "http://localhost:8000/shipments/$(curl -s 'http://localhost:8000/shipments?handle=shipment-demo-1' | jq -r '.shipment.id')/reset" \
  -H 'Content-Type: application/json' \
  -d '{"reason":"manual reset to origin"}' | jq

Reset with manual city name (optimistic check)
curl -s -X POST "http://localhost:8000/shipments/$(curl -s 'http://localhost:8000/shipments?handle=shipment-demo-1' | jq -r '.shipment.id')/reset" \
  -H 'Content-Type: application/json' \
  -d '{"from":"Tokyo","reason":"manual reset to origin"}' | jq


If the shipment already moved away from "Tokyo" between your checks, you’ll get 409 state changed; try again.

Audit (names included)
Full audit
curl -s "http://localhost:8000/shipments/$(curl -s 'http://localhost:8000/shipments?handle=shipment-demo-1' | jq -r '.shipment.id')/audit" | jq

Compact timeline
curl -s "http://localhost:8000/shipments/$(curl -s 'http://localhost:8000/shipments?handle=shipment-demo-1' | jq -r '.shipment.id')/audit" \
| jq -r '.audit[] | "\(.timestamp) | \(.ok) | \(.from_idx)->\(.to_idx) | \(.from_city) -> \(.to_city) | \(.message)"'

Health & Config
curl -s http://localhost:8000/health | jq
curl -s http://localhost:8000/config | jq
