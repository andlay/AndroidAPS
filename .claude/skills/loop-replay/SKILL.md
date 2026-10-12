---
name: loop-replay
description: Replay past Nightscout data through the real DetermineBasalSMB in a closed loop, to see what other profile settings (profile %, I:C, ISF, basal, DynISF strength, SMB settings) would have done. Use when asked to backsolve or tune profile parameters from history, to check a settings change before trying it, or to test how faithfully the app's loop inputs can be rebuilt from Nightscout.
---

# Loop replay

The replay runs the app's own `DetermineBasalSMB` every five minutes over past data. The loop's
inputs are rebuilt the way `OpenAPSSMBPlugin.invoke` builds them, and the loop's temps and SMBs
change the BG it sees next:

`G(k) = G(k-1) + [truth(k) - truth(k-1)] - trueIsf * sum(activitySim - activityReal)`

`truth` is the UKF-smoothed real BG. Meals, exercise and everything else stay as they happened;
only the insulin changes. `trueIsf` is how the body reacts to insulin that differs from what was
really given. It cannot be measured well from closed-loop data, so always run a band of values
(for example 1.5, 2.0 and 2.5 mmol/L per U) and only trust conclusions that hold across the band.

The result is analysis, not a dosing recommendation. Settings changes are for the user and their
diabetes team to decide.

## Parts

- `tools/loop-replay/replay_prep.py` - Nightscout export to the replay input file. Includes the UKF
  port (`ukf.py`) and the device status parser (`ds_parse.py`).
- `plugins/aps/src/jvmTest/kotlin/app/aaps/plugins/aps/replay/` - the Kotlin harness
  (`LoopReplay`, `ReplayInput`) and the entry point `LoopReplayTest`, which does nothing unless
  `REPLAY_IN` is set, so it never runs in CI.
- `AutoIsfRunner` (same folder) - runs the real `OpenAPSAutoISFPlugin` (`autoISF`, `loop_smb`, SMB ratio),
  `GlucoseStatusCalculatorAutoIsf` and `DetermineBasalAutoISF`. The plugin's collaborators are
  reflective stand-ins that answer only what AutoISF asks, so no shipping code changes. Use `aisf=1`;
  `aisf.<PreferenceKeyName>=value` overrides a setting, `aisfScale` scales all glucose weights.
- `tools/loop-replay/gen_jobs.py` - writes a sweep of scenarios, one per line.
- `tools/loop-replay/check_analyze.py` - scores the rebuilt inputs against the phone's own log.

## Steps

1. Export from Nightscout into one folder: `e_*.json` (entries), `t_*.json` (treatments),
   `s_*.json` (device status, which holds every loop run with its reason and console text) and
   `profiles.json`. Never print or store the API token; use it only from an environment variable.
2. `python3 -I tools/loop-replay/replay_prep.py <data dir> <scratch>/replay_in.txt`
3. Check first, sweep second. Run the check and an "as run" replay:
   ```
   REPLAY_IN=<scratch>/replay_in.txt REPLAY_OUT=<scratch>/out REPLAY_JOBS=<scratch>/jobs.txt REPLAY_CHECK=1 \
     ./gradlew :plugins:aps:jvmTest --tests "*LoopReplayTest*" --rerun --no-daemon > <scratch>/replay.log 2>&1
   python3 -I tools/loop-replay/check_analyze.py <scratch>/out/check_dia5.0.tsv [from ms]
   ```
   `--rerun` is needed because environment variables are not task inputs, so Gradle would
   otherwise call the test up to date. A job line is `name key=value ...`; see `Scenario.parse`.
4. Only sweep when the check is good. Aim for: BG exact, IOB within a few hundredths of a unit
   (median), SMB decisions the same in more than 90% of runs, and an "as run" replay
   (`asRun=1`) whose TIR, time low and insulin per day are close to reality.
5. Compare scenarios with the "as run" replay, not with reality. Whatever bias the replay has
   then cancels out.
6. Prefer the relative mode (`asRun=1` plus `pct`, `cr`, `isf`, `basal`): it scales whatever was in
   force at each loop run, so profile switches the user made on purpose (gym, a different I:C for a
   day) stay. The fixed mode puts one profile on every day and so overrides those switches, which
   shows up as extra lows that are not the profile's fault.
7. Each scenario reports two sets of numbers: all readings (`sim_*`) and only readings where the
   real BG was 10 mmol/L or below (`sim10_*`). Above 10, DynISF doses much harder and a fixed
   `trueIsf` is least believable, so the second set leans less on that assumption. Mask on the real
   BG, never on the replayed BG: masking on the replayed BG hides the highs that weaker settings
   cause. A full sweep takes about 15 minutes on 4 cores.

## Traps found the hard way

- **Nightscout keeps the duration a temp really ran, not the planned one.** The loop sees the
  planned remaining time. `replay_prep.py` takes the planned duration from the loop run that asked
  for the temp. Without that, most temp decisions look different from the phone's.
- **Not every request reaches the pump.** In the first data set about one SMB in five that the
  loop asked for was never delivered, and the phone's own IOB confirmed it. Replaying them all made
  the "as run" replay too low. The harness drops an SMB when the real pump dropped the phone's
  request at that moment (`blockSmb`, on by default).
- **Users change preferences mid-period.** UAM, "SMB always", the SMB interval, the profile,
  temp targets and DynISF were all switched within ten days. Read them per run from the log text
  (`ds_parse.py`) and carry each one forward. Do not assume one value for the whole period.
- **Meal data comes from the log.** COB, the carbs in the absorption window and the last carb
  time are taken from the phone's console text ("COB: x / y", "Last carbs N minutes ago"), not
  rebuilt from treatments. Edited or deleted carb entries make the treatments disagree with what
  the phone used.
- **The BG the loop used can change.** The phone fed raw BG to the loop at first and smoothed BG
  later. `replay_prep.py` finds the switch from the logged BG and rebuilds what the loop saw,
  including sensor noise and filter lag.
- **DynISF TDD.** The TDD the phone used can be back-solved from `variable_sens` and BG. Compare
  it with the TDD formula over the delivered insulin to find the effective adjustment factor
  instead of trusting the preference value.
- **Insulin per day hardly changes between scenarios.** Food is fixed, so the replayed loop ends up
  giving about the same total; settings change the timing. Read results as timing, not dose.
- **A lower basal also means smaller SMBs**, because the largest SMB is set in minutes of basal
  (`maxSMBBasalMinutes`). Say so when a basal scale comes out best.
- **Do not limit a regression to BG ≤ 10.** Unlike scoring, fitting on a BG-limited sample selects
  on the outcome: it drops the rises after meals and makes carbs (I:C) look weaker than they are.
- **AutoISF iobTH only acts with "SMB on even targets" enabled**: `loop_smb` checks it inside that
  branch only. With it off, iobTH changes nothing in a replay (and in the app).
- COB and temp targets (including ones set by automations) stay as they really happened. They do
  not react to the replayed BG. Hypo treatments eaten in reality stay in the truth too, which
  favours settings close to the real ones in both directions.
