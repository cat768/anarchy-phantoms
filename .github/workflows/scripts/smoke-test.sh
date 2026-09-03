#!/usr/bin/env bash
# Boots a real Paper OR Folia server (SERVER_TYPE=paper|folia) at
# TARGET_MC_VERSION with the given JAR_PATH dropped into plugins/, and
# checks that the plugin actually loads/enables rather than just
# compiling. Fails (with the plugin's own error printed) if it doesn't.
#
# This is the per-matrix-leg version of the smoke test that used to be
# inlined in build.yml for Paper 1.21.9 only. Behavior/timing/detection
# logic is unchanged from that version - only the target project
# (paper/folia) and version are now parameters instead of hardcoded.
#
# Required env: SERVER_TYPE, TARGET_MC_VERSION, JAR_PATH
# Optional env: UA (User-Agent for Fill API requests)
#               TARGET_CHANNEL (build channel to accept, default STABLE)
#               PLAN_MODE ("none" or "plan", default "none")
#               PLAN_JAR_URL (direct download URL for Plan.jar, only used
#                 when PLAN_MODE=plan; if unset, resolved automatically via
#                 the GitHub Releases API - see resolve_plan_jar_url below)
#
# TARGET_CHANNEL exists for pinned matrix entries (see generate-matrix.sh's
# PINNED_PAPER_VERSIONS) that were added to the matrix without ever having a
# STABLE build - e.g. Paper 1.21.9, which per Fill only ever received ALPHA
# builds before being superseded by 1.21.10. Every dynamically-discovered
# matrix entry still passes/defaults to STABLE, unchanged from before.
#
# PLAN_MODE is what actually splits every (server_type, mc_version, channel)
# leg into the two halves of the four-way testing matrix described in
# build.yml/generate-matrix.sh: "none" boots AnarchyPhantoms by itself,
# "plan" also drops Plan Player Analytics into plugins/ first, so the same
# leg additionally proves out PlanHook's soft-dependency integration
# (com.djrapitops.plan.* on the classpath, DataExtension registration, the
# Query API persistence hookup) rather than just "the jar loads".
set -uo pipefail

: "${SERVER_TYPE:?must be paper or folia}"
: "${TARGET_MC_VERSION:?must be set}"
: "${JAR_PATH:?path to the built plugin jar}"
UA="${UA:-anarchy-phantoms-ci/1.0}"
TARGET_CHANNEL="${TARGET_CHANNEL:-STABLE}"
PLAN_MODE="${PLAN_MODE:-none}"
PLAN_JAR_URL="${PLAN_JAR_URL:-}"

case "$SERVER_TYPE" in
  paper|folia) ;;
  *) echo "::error::SERVER_TYPE must be 'paper' or 'folia', got '$SERVER_TYPE'" >&2; exit 1 ;;
esac

case "$PLAN_MODE" in
  none|plan) ;;
  *) echo "::error::PLAN_MODE must be 'none' or 'plan', got '$PLAN_MODE'" >&2; exit 1 ;;
esac

if [ ! -f "$JAR_PATH" ]; then
  echo "::error::Jar not found at $JAR_PATH" >&2
  exit 1
fi

# Resolves the actual download URL for Plan's latest release asset via the
# GitHub Releases API, rather than trusting a fixed
# ".../releases/latest/download/Plan.jar" URL to exist. That fixed-name
# convenience URL only works if the newest release happens to have an asset
# literally named "Plan.jar" - Plan's release history shows this has NOT
# always been true (many past releases shipped versioned names like
# "Plan-5.2-build-1174.jar"), so hardcoding it is fragile. Falls back to the
# fixed URL only if the API call itself fails (e.g. transient GitHub API
# rate-limiting) - better to attempt the well-known URL than to fail the
# whole leg on an API hiccup.
resolve_plan_jar_url() {
  if [ -n "$PLAN_JAR_URL" ]; then
    return 0
  fi

  local api_response asset_url
  api_response=$(curl -sf -H "User-Agent: $UA" -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/plan-player-analytics/Plan/releases/latest") || {
    echo "::warning::Could not query GitHub API for Plan's latest release - falling back to the fixed .../releases/latest/download/Plan.jar URL."
    PLAN_JAR_URL="https://github.com/plan-player-analytics/Plan/releases/latest/download/Plan.jar"
    return 0
  }

  # Prefer an asset literally named "Plan.jar" (case-insensitive) if present;
  # Plan (unlike PlanFabric.jar, also published on the same release) is the
  # Bukkit/Paper/Folia-side jar this smoke test needs. Falls back to the
  # first asset matching "Plan*.jar" (excluding Fabric/Bungee/Velocity-only
  # names) if no exact "Plan.jar" exists on this particular release.
  asset_url=$(echo "$api_response" | jq -r '
    [.assets[] | select(.name | test("^Plan\\.jar$"; "i"))] as $exact
    | [.assets[] | select(.name | test("^Plan[-_.].*\\.jar$"; "i")) | select(.name | test("fabric|bungee|velocity"; "i") | not)] as $versioned
    | (if ($exact | length) > 0 then $exact[0] elif ($versioned | length) > 0 then $versioned[0] else null end)
    | .browser_download_url // empty
  ')

  if [ -z "$asset_url" ]; then
    echo "::error::Could not find a Plan.jar-like asset on Plan's latest GitHub release."
    return 1
  fi

  PLAN_JAR_URL="$asset_url"
}

run_smoke_test() {
  WORKDIR="$RUNNER_TEMP/smoketest"
  rm -rf "$WORKDIR"
  mkdir -p "$WORKDIR/plugins"

  echo "::group::Resolving $TARGET_CHANNEL $SERVER_TYPE build for $TARGET_MC_VERSION"

  # Exact version match only - unlike the old single-target build.yml step,
  # this script does NOT walk backwards to older versions on a missing
  # build on the target channel. Version selection (including "does this
  # version even have a build on the expected channel") already happened
  # once in generate-matrix.sh; if a version made it into the matrix, it's
  # expected to still have a matching build minutes later. If Fill's data
  # changed underneath us in that window, failing loudly here is more
  # honest than silently testing a different version than the matrix says.
  BUILDS=$(curl -sf -H "User-Agent: $UA" \
    "https://fill.papermc.io/v3/projects/${SERVER_TYPE}/versions/${TARGET_MC_VERSION}/builds") || {
    echo "::error::Could not fetch builds for $SERVER_TYPE $TARGET_MC_VERSION from Fill API"
    return 1
  }
  DOWNLOAD_URL=$(echo "$BUILDS" | jq -r \
    --arg channel "$TARGET_CHANNEL" \
    'map(select(.channel == $channel)) | .[0].downloads."server:default".url // empty')

  if [ -z "$DOWNLOAD_URL" ]; then
    echo "::error::No $TARGET_CHANNEL $SERVER_TYPE build found for $TARGET_MC_VERSION"
    return 1
  fi

  echo "Downloading: $DOWNLOAD_URL"
  curl -sfL -H "User-Agent: $UA" -o "$WORKDIR/server.jar" "$DOWNLOAD_URL"
  echo "::endgroup::"

  cp "$JAR_PATH" "$WORKDIR/plugins/"

  # PLAN_MODE=plan: also drop Plan Player Analytics in, so this leg smoke
  # tests AnarchyPhantoms' PlanHook integration instead of just booting
  # AnarchyPhantoms alone. Plan.jar is fetched fresh every run (always
  # "latest", same as a real server owner would install) rather than pinned,
  # since the point of this leg is catching breakage against whatever Plan
  # currently ships.
  if [ "$PLAN_MODE" = "plan" ]; then
    echo "::group::Downloading Plan Player Analytics"
    if ! resolve_plan_jar_url; then
      echo "::endgroup::"
      return 1
    fi
    echo "Downloading: $PLAN_JAR_URL"
    if ! curl -sfL -H "User-Agent: $UA" -o "$WORKDIR/plugins/Plan.jar" "$PLAN_JAR_URL"; then
      echo "::error::Could not download Plan.jar from $PLAN_JAR_URL"
      echo "::endgroup::"
      return 1
    fi
    echo "::endgroup::"

    # Plan will not finish enabling - it logs an error and aborts geolocation
    # setup - unless the GeoLite2 EULA (https://www.maxmind.com/en/geolite2/eula)
    # is explicitly accepted in its config. Plan has no config file to place
    # this in yet (it writes plugins/Plan/config.yml on first boot), so this
    # pre-seeds it before the server ever starts. Everything else is left at
    # Plan's own defaults: Bukkit/Paper/Folia side, unlike Bungee/Velocity,
    # does NOT require Server.IP or MySQL to enable - SQLite is the default
    # database and the built-in webserver only needs an open local port,
    # which CI provides. Data_gathering.Geolocations is left at its default
    # (true) precisely so this pre-accepted EULA setting is exercised, rather
    # than sidestepped by disabling geolocation instead.
    mkdir -p "$WORKDIR/plugins/Plan"
    cat > "$WORKDIR/plugins/Plan/config.yml" <<'EOF'
Data_gathering:
  Accept_GeoLite2_EULA: true
EOF
  fi

  echo "eula=true" > "$WORKDIR/eula.txt"
  # Headless/offline settings so the server boots without a real
  # network/account context and shuts down cleanly on its own.
  #
  # level-type is "void" (a single-layer superflat with no blocks) rather
  # than a normal flat grass world: this is the cheapest possible world to
  # generate/light, which matters a lot on shared CI hardware where
  # world-gen is what was blowing the timeout, not plugin code.
  # generator-settings explicitly defines a single air layer so we're not
  # relying on an implicit default.
  #
  # generate-structures/spawn-npcs/spawn-monsters are all off to skip
  # structure placement and extra entity/AI setup work that isn't needed
  # just to confirm the plugin enables cleanly.
  {
    echo "online-mode=false"
    echo "server-port=25599"
    echo "level-type=minecraft:flat"
    echo 'generator-settings={"layers":[{"block":"minecraft:air","height":1}],"biome":"minecraft:the_void"}'
    echo "generate-structures=false"
    echo "spawn-npcs=false"
    echo "spawn-monsters=false"
    echo "spawn-protection=0"
    echo "view-distance=3"
    echo "simulation-distance=3"
  } > "$WORKDIR/server.properties"

  echo "::group::Starting $SERVER_TYPE server"
  cd "$WORKDIR"
  # Stream the log to the job's own stdout live (tail -F in the
  # background) so if the server dies before writing much to disk, we
  # still see what happened in the Actions log itself rather than only
  # getting a truncated logs/latest.log after the fact.
  mkdir -p logs
  touch logs/latest.log
  tail -n +1 -F logs/latest.log &
  TAIL_PID=$!

  # The watcher loop below needs to see "Done"/error lines the moment
  # they're printed. logs/latest.log is written by Paper's (and Folia's -
  # same log4j2 base) RollingRandomAccessFile appender, which buffers/
  # flushes on its own schedule - NOT synchronously per line - so under CI
  # contention the console can show "Done" well before those same bytes
  # land on disk, sometimes not until shutdown flushes everything at once.
  # That made the watcher poll an effectively stale file and time out even
  # on a perfectly healthy boot. console.log instead captures the JVM's
  # raw stdout directly via `tee`, with no log4j buffering in between, so
  # a line is visible to the watcher the instant the process writes it.
  touch console.log

  # Feed "stop" after boot via a background watcher instead of relying
  # solely on a timeout, so we get a clean shutdown log when possible.
  #
  # Heap raised to 4G (public-repo runners have 4 vCPU/16GB, so this is
  # safe headroom) and -XX:ActiveProcessorCount is pinned because G1GC's
  # auto-detected thread count can be wrong under the runner's cgroup,
  # which slows world-gen enough to blow a tight timeout even when
  # there's no real OOM. -Xms is set to 2G (rather than 1G) so the heap
  # doesn't have to grow mid-boot on a contended host, which was adding
  # avoidable delay right when startup is most timing-sensitive.
  #
  # Folia note: region-based ticking changes concurrency but not the log
  # lines being watched for below ("Done (" / plugin enable lines still
  # come from the same underlying Paper log format), so no divergent
  # detection logic is needed for Folia vs Paper here.
  #
  # The watcher loop runs for 600s (10 min): observed CI runs have shown a
  # healthy void-world boot occasionally take noticeably longer than a
  # normal 5-10s local boot purely from shared-host contention, so this
  # has more headroom than a tighter window would. The outer `timeout` is
  # given a 30s buffer beyond the watcher so a "Done" detected right at
  # the edge of the window still has time to trigger a clean "stop" and
  # shutdown log before the hard kill, instead of the two racing at the
  # same deadline.
  WATCH_SECS=600
  TIMEOUT_SECS=630
  (
    for _ in $(seq 1 "$WATCH_SECS"); do
      if grep -qE '^\[[0-9:]+ INFO\]: Done \(' console.log 2>/dev/null \
         || grep -qE '\*\*\*.*could not be loaded|Error occurred while enabling|disabled due to' console.log 2>/dev/null; then
        sleep 2
        echo "stop"
        break
      fi
      sleep 1
    done
  ) | timeout "$TIMEOUT_SECS" java -Xmx4G -Xms2G \
        -XX:ActiveProcessorCount=4 \
        -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \
        -jar server.jar --nogui \
        2>&1 | tee console.log \
    || true

  kill "$TAIL_PID" 2>/dev/null || true
  wait "$TAIL_PID" 2>/dev/null || true
  echo "::endgroup::"

  echo "::group::Server log"
  cat logs/latest.log 2>/dev/null || echo "(no log produced)"
  echo "::endgroup::"

  # Verdict is read from console.log, not logs/latest.log, for the same
  # reason the watcher above uses it: log4j2's RollingRandomAccessFile
  # appender buffers/flushes on its own schedule, so logs/latest.log can
  # still be missing "Done" on disk even though the server printed it and
  # shut down cleanly. Checking the buffered file here reintroduces the
  # exact false "stalled boot" failure the watcher was already fixed to
  # avoid.
  LOG="console.log"
  if [ ! -f "$LOG" ] || [ ! -s "$LOG" ]; then
    echo "::error::Server produced no console output at all — it likely failed to start."
    return 1
  fi

  PLUGIN_NAME="AnarchyPhantoms"
  FAILED=0
  # STALL_ONLY stays 1 only if every failure found below is the "never
  # reached Done, but plugin did log output" case with no other error
  # signature present - i.e. a pure slow/stalled boot. Any concrete
  # plugin error (JVM crash, enable failure, plugin never loaded at all)
  # flips this to 0, since retrying won't fix a real bug.
  STALL_ONLY=1

  # Check for a JVM-level death (OOM, crash) before assuming this is a
  # plugin problem — these produce a tiny log with no plugin output at
  # all, which otherwise looks confusingly like the plugin never got a
  # chance to load.
  if grep -qiE "java\.lang\.OutOfMemoryError|A fatal error has been detected by the Java Runtime Environment|Could not reserve enough space" "$LOG"; then
    echo "::error::The JVM itself crashed or ran out of heap before the server finished starting (not a plugin error). Consider raising -Xmx further."
    FAILED=1
    STALL_ONLY=0
  fi

  LOG_SIZE=$(wc -c < "$LOG")
  if [ "$LOG_SIZE" -lt 500 ]; then
    echo "::warning::$LOG is only ${LOG_SIZE} bytes — the server likely died very early (before most logging even started), independent of plugin code."
  fi

  if grep -qE "Error occurred while enabling $PLUGIN_NAME" "$LOG" \
     || grep -qE "Could not load '.*$(basename "$JAR_PATH")'" "$LOG" \
     || grep -qE "$PLUGIN_NAME.*disabled due to" "$LOG"; then
    echo "::error::Plugin failed to enable. See extracted error below."
    FAILED=1
    STALL_ONLY=0
  fi

  if ! grep -qE "\[$PLUGIN_NAME\] Enabling $PLUGIN_NAME" "$LOG"; then
    echo "::error::Never saw '[$PLUGIN_NAME] Enabling $PLUGIN_NAME' in the log — plugin was not loaded by the server."
    FAILED=1
    STALL_ONLY=0
  fi

  if ! grep -qE '^\[[0-9:]+ INFO\]: Done \(' "$LOG"; then
    if grep -qi "$PLUGIN_NAME" "$LOG"; then
      echo "::error::Server never reached 'Done', but $PLUGIN_NAME did log output — this looks like a slow/stalled boot (world-gen, GC, or CI resource contention) rather than a plugin crash. See the log tail below."
    else
      echo "::error::Server never reached 'Done' and never logged anything about $PLUGIN_NAME — it did not finish starting, independent of the plugin."
      STALL_ONLY=0
    fi
    FAILED=1
  fi

  # PLAN_MODE=plan: beyond "did AnarchyPhantoms itself enable", also prove
  # the Plan integration leg actually did something different from the
  # no-plugins leg - i.e. that Plan itself enabled (not just silently failed
  # its own boot, e.g. over a bad EULA setting or a port bind failure) AND
  # that PlanHook detected it and registered the DataExtension. Both are
  # concrete, in this run's console output - not inferred from "no error was
  # printed" - so a regression in either Plan itself or PlanHook's detection
  # logic fails this leg specifically instead of only showing up as a
  # generic AnarchyPhantoms enable failure (or not at all).
  if [ "$PLAN_MODE" = "plan" ]; then
    if grep -qE "Error occurred while enabling Plan" "$LOG" \
       || grep -qE "Plan.*disabled due to" "$LOG"; then
      echo "::error::Plan Player Analytics itself failed to enable. See extracted error below."
      FAILED=1
      STALL_ONLY=0
    elif ! grep -qE "\[Plan\] Enabling Plan" "$LOG"; then
      echo "::error::Never saw '[Plan] Enabling Plan' in the log — Plan was not loaded by the server, so the PlanHook integration was not actually exercised by this run."
      FAILED=1
      STALL_ONLY=0
    fi

    if ! grep -qE "Registered AnarchyPhantoms stats with Plan Player Analytics" "$LOG"; then
      echo "::error::Never saw AnarchyPhantoms' PlanHook register its DataExtension with Plan — the soft-dependency integration did not engage even though Plan was installed."
      FAILED=1
      STALL_ONLY=0
    fi
  fi

  if [ "$FAILED" -ne 0 ]; then
    echo "## ❌ Plugin smoke test failed ($SERVER_TYPE $TARGET_MC_VERSION, plan=$PLAN_MODE)" >> "$GITHUB_STEP_SUMMARY"
    echo '```' >> "$GITHUB_STEP_SUMMARY"
    RELEVANT=$(awk '/\[AnarchyPhantoms\]|\[Plan\]|Error occurred while enabling|Caused by:|\tat /' "$LOG" | tail -n 100)
    if [ -n "$RELEVANT" ]; then
      printf '%s\n' "$RELEVANT" >> "$GITHUB_STEP_SUMMARY"
    else
      tail -n 100 "$LOG" >> "$GITHUB_STEP_SUMMARY"
    fi
    echo '```' >> "$GITHUB_STEP_SUMMARY"
    if [ "$STALL_ONLY" -eq 1 ]; then
      return 2
    fi
    return 1
  fi

  if [ "$PLAN_MODE" = "plan" ]; then
    echo "## ✅ Plugin loaded and enabled successfully on $SERVER_TYPE $TARGET_MC_VERSION, with Plan Player Analytics integration confirmed" >> "$GITHUB_STEP_SUMMARY"
  else
    echo "## ✅ Plugin loaded and enabled successfully on $SERVER_TYPE $TARGET_MC_VERSION (no plugins)" >> "$GITHUB_STEP_SUMMARY"
  fi
}

ATTEMPTS=2
STATUS=0
for ATTEMPT in $(seq 1 "$ATTEMPTS"); do
  echo "::group::Smoke test attempt $ATTEMPT/$ATTEMPTS ($SERVER_TYPE $TARGET_MC_VERSION, plan=$PLAN_MODE)"
  run_smoke_test
  STATUS=$?
  echo "::endgroup::"
  if [ "$STATUS" -eq 0 ]; then
    break
  fi
  if [ "$STATUS" -eq 2 ] && [ "$ATTEMPT" -lt "$ATTEMPTS" ]; then
    echo "::warning::Attempt $ATTEMPT looked like a CI stall (server never reached 'Done', no concrete plugin error) rather than a plugin bug. Retrying once before failing the job."
    continue
  fi
  break
done
exit "$STATUS"