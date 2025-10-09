# app.py
import os, random
from datetime import datetime, timezone
from typing import Optional, Dict, Any, Union
from flask import Flask, request, jsonify
from flask_sqlalchemy import SQLAlchemy
from sqlalchemy.exc import IntegrityError

# -------------------
# Config / Environment
# -------------------
PORT = int(os.getenv("PORT", "8000"))
DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///app.db")

# Chaos (random 503 on mutating endpoints)
FAILURE_RATE = float(os.getenv("FAILURE_RATE", "0.3"))
RETRY_AFTER_MIN = int(os.getenv("RETRY_AFTER_MIN", "1"))
RETRY_AFTER_MAX = int(os.getenv("RETRY_AFTER_MAX", "3"))

# Movement rules
ENFORCE_ADJACENT = os.getenv("ENFORCE_ADJACENT", "1") == "1"

# Reset behavior (no locks)
ALLOW_RESET_WITHOUT_LOCK = os.getenv("ALLOW_RESET_WITHOUT_LOCK", "1") == "1"

# -------------
# Flask / DB
# -------------
app = Flask(__name__)
app.config["SQLALCHEMY_DATABASE_URI"] = DATABASE_URL
app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False
db = SQLAlchemy(app)

# -------------
# Models
# -------------
class Waypoint(db.Model):
    __tablename__ = "waypoints"
    id = db.Column(db.Integer, primary_key=True)
    idx = db.Column(db.Integer, nullable=False, unique=True, index=True)
    handle = db.Column(db.String(80), nullable=False, unique=True, index=True)
    city = db.Column(db.String(120), nullable=False)
    capacity = db.Column(db.Integer, nullable=False, default=1)  # enforce 1 active shipment
    occupied_by_shipment_id = db.Column(db.Integer, db.ForeignKey("shipments.id"), nullable=True)

    # kept for backward compat (unused in lock-less build)
    lock_token = db.Column(db.String(64), nullable=True)
    lock_expires_at = db.Column(db.DateTime, nullable=True)

    created_at = db.Column(db.DateTime, default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = db.Column(
        db.DateTime,
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
        nullable=False,
    )
# Waypoint model for locations in the route

class Shipment(db.Model):
    __tablename__ = "shipments"
    id = db.Column(db.Integer, primary_key=True)
    handle = db.Column(db.String(80), nullable=False, unique=True, index=True)
    name = db.Column(db.String(120), nullable=False)
    status = db.Column(db.String(40), nullable=False, default="IN_TRANSIT")
    current_idx = db.Column(db.Integer, nullable=False, default=0)
    created_at = db.Column(db.DateTime, default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = db.Column(
        db.DateTime,
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
        nullable=False,
    )
# Shipment model for goods being transported

class MovementAudit(db.Model):
    __tablename__ = "movement_audit"
    id = db.Column(db.Integer, primary_key=True)
    shipment_id = db.Column(db.Integer, db.ForeignKey("shipments.id"), nullable=False, index=True)
    from_idx = db.Column(db.Integer, nullable=False)
    to_idx = db.Column(db.Integer, nullable=False)
    ok = db.Column(db.Boolean, nullable=False)
    message = db.Column(db.String(255), nullable=True)
    created_at = db.Column(db.DateTime, default=lambda: datetime.now(timezone.utc), nullable=False)
# MovementAudit model to log shipment movements

with app.app_context():
    db.create_all()
# Create database tables if they don't exist

# ----------------
# Seed waypoints
# ----------------
CITIES_POOL = [
    "Honolulu","Tokyo","Seoul","Shanghai","Beijing","Hong Kong","Bangkok","Kuala Lumpur","Singapore","Jakarta",
    "Manila","Hanoi","Ho Chi Minh City","Colombo","Dhaka","Kolkata","Hyderabad","Chennai","Mumbai","Bangalore"
]

def seed_waypoints_if_empty(n: int = 10) -> None:
    if Waypoint.query.count() > 0:
        return
    picks = random.sample(CITIES_POOL, n)
    for i, city in enumerate(picks):
        db.session.add(Waypoint(idx=i, handle=f"wp-{i}", city=city, capacity=1))
    db.session.commit()
    print(f"[seed] Seeded {n} waypoints (idx 0..{n-1})")
# Seeds initial waypoints if the table is empty

with app.app_context():
    seed_waypoints_if_empty()

# ----------------
# Chaos middleware
# ----------------
@app.before_request
def chaos():
    if request.method in ("POST", "PUT", "PATCH", "DELETE"):
        endpoint_path = request.path

        if request.method == "POST" and endpoint_path == "/shipments":
            return None

#         if request.method == "POST" and endpoint_path.endswith("/reset"):
#             return None

        if request.method == "GET" and endpoint_path == "/route":
            return None

        import random as _r
        if _r.random() < max(0.0, min(1.0, FAILURE_RATE)):
            return (
                jsonify({"success": False, "error": "Simulated transient failure. Please retry."}),
                503,
                {"Retry-After": str(random.randint(RETRY_AFTER_MIN, RETRY_AFTER_MAX))},
            )
# Randomly returns a 503 error for mutating requests based on FAILURE_RATE

# ----------------
# Helpers
# ----------------
def wp_view(w: Waypoint) -> Dict[str, Any]:
    occupant = None
    if w.occupied_by_shipment_id:
        s = Shipment.query.get(w.occupied_by_shipment_id)
        if s:
            occupant = {"id": s.id, "handle": s.handle, "status": s.status, "current_idx": s.current_idx}
    return {
        "id": w.id,
        "idx": w.idx,
        "handle": w.handle,
        "city": w.city,
        "capacity": w.capacity,
        "occupied_by_shipment_id": w.occupied_by_shipment_id,
        "occupied_by": occupant,
    }
# Formats a Waypoint object into a dictionary for API response

def shipment_view(s: Shipment) -> Dict[str, Any]:
    return {"id": s.id, "handle": s.handle, "name": s.name, "status": s.status, "current_idx": s.current_idx}
# Formats a Shipment object into a dictionary for API response

def normalize_idx(x: Union[str, int]) -> Optional[int]:
    """
    Accepts:
      - int index
      - "3"
      - waypoint handle (e.g., "wp-3")
      - CITY NAME (case-insensitive, e.g., "Tokyo")
    Returns idx or None.
    """
    if isinstance(x, int):
        return x
    if isinstance(x, str):
        xs = x.strip()
        if xs.isdigit():
            return int(xs)
        w = Waypoint.query.filter_by(handle=xs).first()
        if w:
            return w.idx
        w = Waypoint.query.filter(Waypoint.city.ilike(xs)).first()
        if w:
            return w.idx
    return None
# Converts various inputs (int, string index, handle, city name) into a waypoint index (int)

# -------------
# System info
# -------------
@app.get("/health")
def health():
    return jsonify({"ok": True})
# Health check endpoint

@app.get("/config")
def config():
    return jsonify({
        "database_url": DATABASE_URL,
        "failure_rate": FAILURE_RATE,
        "retry_after_min": RETRY_AFTER_MIN,
        "retry_after_max": RETRY_AFTER_MAX,
        "enforce_adjacent": ENFORCE_ADJACENT,
        "allow_reset_without_lock": ALLOW_RESET_WITHOUT_LOCK,
    })
# Returns current application configuration

# -----------------------
# Route & Waypoint APIs
# -----------------------
@app.get("/waypoints")
def list_waypoints():
    wps = Waypoint.query.order_by(Waypoint.idx.asc()).all()
    return jsonify({"count": len(wps), "waypoints": [wp_view(w) for w in wps]})
# Lists all waypoints in ascending index order

@app.get("/route")
def route_cities():
    wps = Waypoint.query.order_by(Waypoint.idx.asc()).all()
    return jsonify({
        "count": len(wps),
        "order": [{"idx": w.idx, "city": w.city, "handle": w.handle, "id": w.id} for w in wps]
    })
# Returns the ordered list of waypoints defining the route

@app.get("/waypoints/<int:wp_id>")
def get_waypoint(wp_id: int):
    w = Waypoint.query.get(wp_id)
    if not w:
        return jsonify({"success": False, "error": "not found"}), 404
    return jsonify({"success": True, "waypoint": wp_view(w)})
# Gets a specific waypoint by its ID

@app.get("/waypoints/handle/<string:handle>")
def get_waypoint_by_handle(handle: str):
    w = Waypoint.query.filter_by(handle=handle).first()
    if not w:
        return jsonify({"success": False, "error": "not found"}), 404
    return jsonify({"success": True, "waypoint": wp_view(w)})
# Gets a specific waypoint by its handle

@app.get("/waypoints/city/<string:city>")
def get_waypoint_by_city(city: str):
    w = Waypoint.query.filter(Waypoint.city.ilike(city)).first()
    if not w:
        return jsonify({"success": False, "error": "not found"}), 404
    return jsonify({"success": True, "waypoint": wp_view(w)})
# Gets a specific waypoint by its city name (case-insensitive)

# -------------
# Shipments
# -------------
@app.post("/shipments")
def create_shipment():
    body = request.get_json(force=True, silent=True) or {}
    handle = body.get("handle")
    name = body.get("name") or handle or "Shipment"
    if not handle:
        return jsonify({"success": False, "error": "handle required"}), 400

    origin = Waypoint.query.filter_by(idx=0).first()
    if not origin:
        return jsonify({"success": False, "error": "no origin waypoint"}), 400
    if origin.occupied_by_shipment_id is not None:
        return jsonify({"success": False, "error": "origin occupied"}), 409

    try:
        s = Shipment(handle=handle, name=name, status="IN_TRANSIT", current_idx=0)
        db.session.add(s)
        db.session.flush()
        origin.occupied_by_shipment_id = s.id
        db.session.add(MovementAudit(shipment_id=s.id, from_idx=-1, to_idx=0, ok=True, message="created at origin"))
        db.session.commit()
        return jsonify({"success": True, "shipment": shipment_view(s)}), 201
    except IntegrityError:
        db.session.rollback()
        return jsonify({"success": False, "error": "duplicate handle or origin race"}), 409
    except Exception as e:
        db.session.rollback()
        return jsonify({"success": False, "error": f"server error: {e}"}), 500
# Creates a new shipment at the origin waypoint (idx 0)

@app.get("/shipments")
def shipments_lookup():
    handle = request.args.get("handle")
    if not handle:
        return jsonify({"success": False, "error": "handle query param required"}), 400
    s = Shipment.query.filter_by(handle=handle).first()
    if not s:
        return jsonify({"success": False, "error": "not found"}), 404
    return jsonify({"success": True, "shipment": shipment_view(s)})
# Looks up a shipment by its handle


@app.get("/shipments/<int:shipment_id>")
def get_shipment(shipment_id: int):
    s = Shipment.query.get(shipment_id)
    if not s:
        return jsonify({"success": False, "error": "not found"}), 404
    return jsonify({"success": True, "shipment": shipment_view(s)})
# Gets a specific shipment by its ID

# -------------
# Move (by city / handle / idx), adjacency enforced (default)
# -------------
@app.post("/move")
def move():
    b = request.get_json(force=True, silent=True) or {}
    shipment_id = b.get("shipment_id")
    frm = b.get("from")
    to = b.get("to")
    if shipment_id is None or frm is None or to is None:
        return jsonify({"success": False, "error": "shipment_id, from, to required"}), 400

    from_idx = normalize_idx(frm)
    to_idx = normalize_idx(to)
    if from_idx is None or to_idx is None:
        return jsonify({"success": False, "error": "invalid from/to"}), 400

    if ENFORCE_ADJACENT and abs(to_idx - from_idx) != 1:
        return jsonify({"success": False, "error": "non-adjacent move not allowed"}), 422

    s = Shipment.query.get(shipment_id)
    wp_from = Waypoint.query.filter_by(idx=from_idx).first()
    wp_to = Waypoint.query.filter_by(idx=to_idx).first()
    if not s or not wp_from or not wp_to:
        return jsonify({"success": False, "error": "shipment or waypoints not found"}), 404

    # Validate occupancy and capacity
    if s.current_idx != from_idx:
        db.session.add(MovementAudit(shipment_id=s.id, from_idx=from_idx, to_idx=to_idx, ok=False, message="shipment not at 'from'"))
        db.session.commit()
        return jsonify({"success": False, "error": "shipment not at 'from'"}), 409

    if wp_from.occupied_by_shipment_id != s.id:
        db.session.add(MovementAudit(shipment_id=s.id, from_idx=from_idx, to_idx=to_idx, ok=False, message="'from' not occupied by this shipment"))
        db.session.commit()
        return jsonify({"success": False, "error": "'from' not occupied by this shipment"}), 409

    if wp_to.capacity != 1 or wp_to.occupied_by_shipment_id is not None:
        db.session.add(MovementAudit(shipment_id=s.id, from_idx=from_idx, to_idx=to_idx, ok=False, message="destination occupied"))
        db.session.commit()
        return jsonify({"success": False, "error": "destination occupied"}), 409

    try:
        wp_from.occupied_by_shipment_id = None

        wp_to.occupied_by_shipment_id = s.id
        s.current_idx = to_idx
        s.updated_at = datetime.now(timezone.utc)

        db.session.add(MovementAudit(shipment_id=s.id, from_idx=from_idx, to_idx=to_idx, ok=True, message="move ok"))

        last_idx = db.session.query(db.func.max(Waypoint.idx)).scalar()
        if to_idx == last_idx:
            wp_to.occupied_by_shipment_id = None
            s.status = "COMPLETED"
            s.updated_at = datetime.now(timezone.utc)
            db.session.add(MovementAudit(shipment_id=s.id, from_idx=to_idx, to_idx=to_idx, ok=True, 
                message="shipment completed at final destination"
            ))
            
        db.session.commit()
        return jsonify({"success": True, "shipment": shipment_view(s)}), 200
    except IntegrityError:
        db.session.rollback()
        db.session.add(MovementAudit(shipment_id=s.id, from_idx=from_idx, to_idx=to_idx, ok=False, message="integrity error"))
        db.session.commit()
        return jsonify({"success": False, "error": "integrity error"}), 409
    except Exception as e:
        db.session.rollback()
        return jsonify({"success": False, "error": f"server error: {e}"}), 500
# Handles a shipment moving from one waypoint to an adjacent one, with occupancy and adjacency checks

# -------------
# Reset to origin (NO LOCKS)
# -------------
@app.post("/shipments/<int:shipment_id>/reset")
def reset_to_origin(shipment_id: int):
    """
    Resets the shipment back to origin (idx 0) without locking.
    - If 'from' provided, we verify the shipment is still there (optimistic check).
    - If 'from' omitted, we use the shipment's current index.
    """
    if not ALLOW_RESET_WITHOUT_LOCK:
        return jsonify({"success": False, "error": "reset without lock disabled"}), 403

    b = request.get_json(force=True, silent=True) or {}
    frm = b.get("from")  # optional (city/handle/idx)
    reason = b.get("reason", "reset to origin")

    s = Shipment.query.get(shipment_id)
    wp_origin = Waypoint.query.filter_by(idx=0).first()
    if not s or not wp_origin:
        return jsonify({"success": False, "error": "shipment or origin not found"}), 404

    # Determine from_idx
    if frm is None:
        from_idx = s.current_idx
    else:
        from_idx = normalize_idx(frm)
        if from_idx is None:
            return jsonify({"success": False, "error": "invalid 'from'"}), 400

    wp_from = Waypoint.query.filter_by(idx=from_idx).first()
    if not wp_from:
        return jsonify({"success": False, "error": "from waypoint not found"}), 404

    # Optimistic validation
    if s.current_idx != from_idx or wp_from.occupied_by_shipment_id != s.id:
        db.session.add(MovementAudit(
            shipment_id=shipment_id, from_idx=from_idx, to_idx=0, ok=False,
            message="reset failed: state changed (shipment not at 'from')"
        ))
        db.session.commit()
        return jsonify({"success": False, "error": "state changed; try again"}), 409

    if from_idx == 0:
        db.session.add(MovementAudit(shipment_id=s.id, from_idx=0, to_idx=0, ok=True, message=f"reset noop: {reason}"))
        db.session.commit()
        return jsonify({"success": True, "shipment": shipment_view(s), "note": "already at origin"}), 200

    if wp_origin.occupied_by_shipment_id not in (None, s.id):
        db.session.add(MovementAudit(shipment_id=s.id, from_idx=from_idx, to_idx=0, ok=False, message="reset failed: origin occupied"))
        db.session.commit()
        return jsonify({"success": False, "error": "origin occupied"}), 409

    try:
        # Move back to origin
        wp_from.occupied_by_shipment_id = None
        wp_origin.occupied_by_shipment_id = s.id
        s.current_idx = 0
        s.updated_at = datetime.now(timezone.utc)

        db.session.add(MovementAudit(shipment_id=s.id, from_idx=from_idx, to_idx=0, ok=True, message=f"reset: {reason}"))
        db.session.commit()
        return jsonify({"success": True, "shipment": shipment_view(s)}), 200
    except Exception as e:
        db.session.rollback()
        db.session.add(MovementAudit(shipment_id=s.id, from_idx=from_idx, to_idx=0, ok=False, message=f"reset failed: {e}"))
        db.session.commit()
        return jsonify({"success": False, "error": f"server error: {e}"}), 500
# Resets a shipment back to the origin waypoint (idx 0), with optimistic concurrency check

# -------------
# Audit (enriched with city/handle)
# -------------
@app.get("/shipments/<int:shipment_id>/audit")
def shipment_audit(shipment_id: int):
    s = Shipment.query.get(shipment_id)
    if not s:
        return jsonify({"success": False, "error": "not found"}), 404

    # map index -> waypoint
    wps = {w.idx: w for w in Waypoint.query.all()}
    logs = (
        MovementAudit.query.filter_by(shipment_id=shipment_id)
        .order_by(MovementAudit.created_at.asc(), MovementAudit.id.asc())
        .all()
    )

    enriched = []
    for a in logs:
        f = wps.get(a.from_idx)
        t = wps.get(a.to_idx)
        enriched.append({
            "id": a.id,
            "from_idx": a.from_idx,
            "from_city": f.city if f else None,
            "from_handle": f.handle if f else None,
            "to_idx": a.to_idx,
            "to_city": t.city if t else None,
            "to_handle": t.handle if t else None,
            "ok": a.ok,
            "message": a.message,
            "timestamp": a.created_at.isoformat()
        })

    return jsonify({"success": True, "shipment": shipment_view(s), "audit": enriched})
# Retrieves and enriches the movement audit log for a specific shipment


@app.delete("/shipments")
def delete_all_shipments():
    try:
        num = Shipment.query.delete()
        # also clear occupied_by_shipment_id from all waypoints
        Waypoint.query.update({Waypoint.occupied_by_shipment_id: None})
        db.session.commit()
        return jsonify({"success": True, "deleted_shipments": num}), 200
    except Exception as e:
        db.session.rollback()
        return jsonify({"success": False, "error": str(e)}), 500


# -------------
# Run
# -------------
if __name__ == "__main__":
    app.run(host="0.0.0.0", port=PORT, debug=os.getenv("FLASK_DEBUG", "0") == "1")
# Runs the Flask application