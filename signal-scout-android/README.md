# Signal Scout Android

Native Android prototype for live GPS tracking and estimated cell-mast coverage warnings.

## What it does
- Foreground GPS tracking that can continue with the screen off.
- Queries OpenStreetMap Overpass for mapped mobile-phone masts around the current location.
- Estimates coverage with an adjustable mast-radius model.
- Projects the current heading to estimate the first point outside the modeled coverage.
- Sends a high-priority notification a configurable number of minutes before the estimated edge.
- Includes an offline-style coverage radar (the mast query itself needs data access).

## Accuracy caveat
This is an experimental estimate. Real coverage depends on carrier ownership, frequencies, antenna sectors, handoffs, terrain, buildings, network load and unmapped infrastructure.
