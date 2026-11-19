# Cloudbase: notes for Claude

Android paragliding instrument, flown in New Zealand. See README.md for what it does.

- `core/` is pure Kotlin with no Android dependency and holds everything safety-relevant.
  Change behaviour there, with a test; `FlightComputerTest` runs whole simulated flights
  (`FlightSim`) through the real pipeline and is the place to prove an end-to-end change.
- `app/` is framework views only (no AppCompat/Material/XML layouts/map SDK). Keep it that way.
- Everything in `Hub` and `FlightComputer` is main-thread only, on purpose. Background threads
  (tile loading, airspace parsing, downloads) post results back to the main looper.
- Build: `make test`, `make build`, `make screenshots`, all running Gradle in a docker container
  via `scripts/gradle.sh` (the host needs no JDK or SDK). Screenshots: Robolectric renders the demo flight to
  `docs/screenshots/`; needs network for map tiles, and `AIRSPACE_SAMPLES=<dir with
  nz_asp_v2.txt>` (mounted into the container) or it shows no airspace. Look at the screenshots after any UI change.
- Airspace data is openAIP OpenAir exports. For NZ the designator prefix in the name
  (NZA/NZB/NZC/NZD/NZG/NZM/NZR/NZV) is authoritative and the class field is not: see
  `AirspacePolicy`.
- Units inside the code are SI (metres, m/s, degrees true); conversion happens only in
  `ui/Theme.kt` `Units`.
- `app/src/main/assets/demo_flight.igc` is the user's own real flight (track data only). It is
  the demo, and `RealFlightTest` replays it through the whole pipeline: the only test against
  real flying. It has no accelerometer data. Beyond that one log, nothing has been validated
  in the air; do not describe filter tuning or alert timing as verified.
