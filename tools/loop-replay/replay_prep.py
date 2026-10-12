"""
Build the input file for the Kotlin loop replay (real DetermineBasalSMB).

Lines (space separated, one record per line):
  P key value                      settings
  CB sec rate                      candidate basal schedule (local seconds of day)
  CT sec low high                  candidate targets (mg/dL)
  DAY ms                           local midnights (for TDD days)
  G ts truth raw                   one per CGM reading; truth = full UKF (RTS) BG
  V i o0 .. o9                     loop view at reading i: view(i-k) - truth(i-k)
  B ts units kind                  bolus; kind SMB | MANUAL
  T ts rate minutes                temp basal (0 minutes = cancel)
  C ts grams                       carbs
  OB ts rate                       observed scheduled basal from this time on
  R ts ...                         one logged loop run (see RUN_FIELDS)
Data dir: Nightscout exports e_*.json (entries), t_*.json (treatments), s_*.json (device status),
profiles.json (profile store), as fetched with the API.
Usage: python3 -I replay_prep.py <data dir> <out file>
"""
import bisect, glob, json, math, os, sys, datetime as dt
from zoneinfo import ZoneInfo

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ukf
from ds_parse import runs, ms

D, OUT = sys.argv[1], sys.argv[2]
MIN = 60_000


def load(prefix):
    out = []
    for f in glob.glob(f"{D}/{prefix}_*.json"):
        out += json.load(open(f))
    return out


entries = sorted({e['date']: e for e in load('e') if e.get('sgv')}.values(), key=lambda e: e['date'])
treat = list({(t.get('created_at'), t.get('eventType'), t.get('insulin'), t.get('carbs'), t.get('duration'), t.get('absolute')): t
              for t in load('t')}.values())
profiles = sorted(json.load(open(f"{D}/profiles.json")), key=lambda p: p.get('mills', 0))
# local midnights and the basal schedule need the user's time zone: take it from the profile
TZ = ZoneInfo(profiles[-1]['store'][profiles[-1]['defaultProfile']].get('timezone') or 'UTC')
R = runs(D)
algo = {}
for r in load('s'):
    s = (r.get('openaps') or {}).get('suggested')
    if s and 'bg' in s:
        algo[ms(s['timestamp'])] = s.get('algorithm') or 'SMB'

raw = [(e['date'], float(e['sgv'])) for e in entries]
truth = ukf.smooth(raw)
ts = [t for t, _ in raw]

# When did the phone start to feed smoothed BG to the loop? First run of a long stretch of non-integer BG.
switch = None
for k in range(len(R) - 20):
    if sum(not float(r['bg']).is_integer() for r in R[k:k + 20]) >= 15:
        switch = R[k]['ts']
        break

lines = []
P = lambda k, v: lines.append(f"P {k} {v}")
P('smooth_switch', switch or 0)

# ---- candidate profile: latest DefaultProfile in Nightscout, basal rounded to the pump step like the phone
store = profiles[-1]['store'][profiles[-1]['defaultProfile']]
for b in store['basal']:
    lines.append(f"CB {b['timeAsSeconds']} {round(b['value'] / 0.05) * 0.05:.2f}")
lows = {x['timeAsSeconds']: x['value'] for x in store['target_low']}
highs = {x['timeAsSeconds']: x['value'] for x in store['target_high']}
for sec in sorted(lows):
    lines.append(f"CT {sec} {lows[sec]} {highs.get(sec, lows[sec])}")
P('cand_sens', store['sens'][0]['value'])
P('cand_cr', store['carbratio'][0]['value'])

# ---- local midnights
d0 = dt.datetime.fromtimestamp(raw[0][0] / 1000 - 3 * 86400, TZ).date()
d1 = dt.datetime.fromtimestamp(raw[-1][0] / 1000, TZ).date()
d = d0
while d <= d1 + dt.timedelta(days=1):
    lines.append(f"DAY {int(dt.datetime(d.year, d.month, d.day, tzinfo=TZ).timestamp() * 1000)}")
    d += dt.timedelta(days=1)

# ---- BG: truth and the view the loop had
for (t, g), sm in zip(raw, truth):
    lines.append(f"G {t} {sm:.2f} {g:.0f}")
WIN = 96
for i in range(len(raw)):
    if switch and raw[i][0] >= switch:
        seg = raw[max(0, i - WIN):i + 1]
        view = ukf.smooth(seg)[-10:]
        view = view[::-1]  # newest first
    else:
        view = [raw[i - k][1] if i - k >= 0 else raw[0][1] for k in range(10)]
    offs = [view[k] - truth[max(0, i - k)] for k in range(min(10, len(view)))]
    offs += [0.0] * (10 - len(offs))
    lines.append("V %d %s" % (i, ' '.join(f"{o:.2f}" for o in offs)))


def iso(s):
    return int(dt.datetime.fromisoformat(s.replace('Z', '+00:00')).timestamp() * 1000)


# ---- deliveries and carbs
run_ts = [r['ts'] for r in R]
for t in treat:
    et = t.get('eventType')
    if t.get('insulin') and et in ('Correction Bolus', 'Meal Bolus', 'SMB'):
        kind = 'SMB' if t.get('automatic') else 'MANUAL'
        lines.append(f"B {iso(t['created_at'])} {t['insulin']} {kind}")
    if et == 'Temp Basal' and t.get('absolute') is not None:
        # Nightscout keeps the duration the temp really ran. The planned one comes from the loop run that asked for it.
        ts0 = iso(t['created_at'])
        planned = t.get('duration') or 0
        k = bisect.bisect_right(run_ts, ts0) - 1
        while k >= 0 and ts0 - R[k]['ts'] <= 3 * MIN:
            if R[k]['rate'] is not None and R[k]['duration'] and abs(R[k]['rate'] - t['absolute']) <= 0.051:
                planned = max(planned, R[k]['duration'])
                break
            k -= 1
        lines.append(f"T {ts0} {t['absolute']} {t.get('duration') or 0} {planned}")
    if t.get('carbs'):
        lines.append(f"C {iso(t['created_at'])} {t['carbs']}")

# ---- observed scheduled basal: from each Nightscout profile store, then from the loop's own log line
for p in profiles:
    st_ = p['store'][p['defaultProfile']]
    start = iso(p['startDate']) if isinstance(p.get('startDate'), str) else p.get('mills', 0)
    # expand the schedule for the days this store was in force (until the next store)
    nxt = [q for q in profiles if (q.get('mills', 0) > p.get('mills', 0))]
    end = nxt[0].get('mills', start) if nxt else raw[-1][0]
    day = dt.datetime.fromtimestamp(start / 1000, TZ).date()
    while True:
        mid = dt.datetime(day.year, day.month, day.day, tzinfo=TZ)
        for b in st_['basal']:
            bt = int((mid + dt.timedelta(seconds=b['timeAsSeconds'])).timestamp() * 1000)
            if start <= bt < end:
                lines.append(f"OB {bt} {round(b['value'] / 0.05) * 0.05:.2f} store")
        if mid.timestamp() * 1000 > end:
            break
        day += dt.timedelta(days=1)
    lines.append(f"OB {start} {round(st_['basal'][0]['value'] / 0.05) * 0.05:.2f} store")
for r in R:
    if r['basal'] is not None:
        lines.append(f"OB {r['ts']} {r['basal']} log")

# ---- logged loop runs
RUN_FIELDS = ['ts', 'algo', 'bg', 'target', 'ratio', 'dyn', 'vsens', 'cob', 'iob', 'smb_allowed', 'psens', 'cr', 'basal',
              'rate', 'duration', 'units', 'eventual', 'insulinReq', 'iob_obs', 'basaliob_obs', 'activity_obs', 'meal_carbs', 'last_carb_age', 'uam', 'smb_always', 'smb_interval', 'smb_failed', 'temp_failed']
# settings the user changed during the period: carry the last value seen forward
last = {'uam': 1, 'smb_always': 1, 'smb_interval': 3}
for r in R:
    for key in last:
        seen = r[key + '_seen']
        if seen is not None:
            last[key] = seen
        r[key] = last[key]
# requests the pump never carried out (the phone's own IOB confirms they were not given)
smb_ts = sorted(iso(t['created_at']) for t in treat if t.get('insulin') and t.get('automatic'))
temp_list = sorted((iso(t['created_at']), t['absolute'], t.get('duration') or 0) for t in treat
                   if t.get('eventType') == 'Temp Basal' and t.get('absolute') is not None)
temp_ts = [t[0] for t in temp_list]


def running_rate(ts):
    k = bisect.bisect_right(temp_ts, ts) - 1
    if k >= 0 and ts < temp_list[k][0] + temp_list[k][2] * MIN:
        return temp_list[k][1]
    return None


for r in R:
    r['smb_failed'] = 0
    r['temp_failed'] = 0
    if r['units']:
        k = bisect.bisect_left(smb_ts, r['ts'])
        if not any(0 <= smb_ts[j] - r['ts'] <= 4 * MIN for j in range(k, min(k + 3, len(smb_ts)))):
            r['smb_failed'] = 1
    if r['rate'] is not None:
        cur = running_rate(r['ts'])
        if cur is None or abs(cur - r['rate']) > 0.051:
            k = bisect.bisect_left(temp_ts, r['ts'])
            if not any(0 <= temp_ts[j] - r['ts'] <= 4 * MIN and abs(temp_list[j][1] - r['rate']) <= 0.06 for j in range(k, min(k + 3, len(temp_ts)))):
                r['temp_failed'] = 1
P('run_fields', ','.join(RUN_FIELDS))
for r in R:
    r = dict(r, algo=algo.get(r['ts'], 'SMB'))
    vals = []
    for f in RUN_FIELDS:
        v = r.get(f)
        if isinstance(v, bool):
            v = int(v)
        vals.append('NA' if v is None else str(v))
    lines.append('R ' + ' '.join(vals))

open(OUT, 'w').write('\n'.join(lines) + '\n')
print(f"wrote {len(lines)} lines; smoothing switch {dt.datetime.fromtimestamp(switch / 1000, TZ) if switch else None}; "
      f"{len(raw)} BG, {len(R)} runs")
