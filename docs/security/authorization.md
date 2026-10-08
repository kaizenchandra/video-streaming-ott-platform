# Authorization

Viewer requests derive account from validated issuer+sub, never body accountId. Every profile/device/watch operation requires owner check. Non-owned IDs return404 to prevent enumeration. Internal eligibility endpoints require workload scope and audience; admin/editor/support require named operation scopes with method-level enforcement and request-level default deny.

Profile maturity enforces content rating ceiling, kids flag and guardian reauthentication before restrictions are weakened. Playback requires active entitlement, published asset, valid rights/country/device/DRM, and stream capacity. Missing/stale/unknown critical eligibility denies. Support fields minimized and read purpose audited. Feature flags cannot disable these checks.

Gateway geo assertion requires trusted network boundary or signed short-lived assertion verified by playback; client headers stripped. UUID possession alone is never authorization. Rate limits applied by principal+route+source prefix where appropriate without storing full sensitive IP indefinitely. Revocation events accelerate cache eviction; grant expiry remains independent safety bound.
