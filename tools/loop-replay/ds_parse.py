"""Parse devicestatus loop runs into flat records (per loop run)."""
import json, glob, re, datetime as dt


def ms(v):
    if isinstance(v, (int, float)):
        return int(v)
    return int(dt.datetime.fromisoformat(v.replace('Z', '+00:00')).timestamp() * 1000)


def num(pat, s, default=None):
    m = re.search(pat, s)
    return float(m.group(1)) if m else default


def uam_state(reason, errs):
    """1 if UAM predictions were made, 0 if they should have been (rising or COB) but were not, else None."""
    if 'UAMpredBG' in reason:
        return 1
    dev = num(r'Dev: (-?[\d.]+)', reason)
    cob = num(r'COB: ([\d.]+)', reason)
    if (dev is not None and dev > 0) or (cob is not None and cob > 0):
        return 0
    return None


def smb_always_state(errs):
    if 'SMB enabled due to enableSMB_always' in errs:
        return 1
    if re.search(r'SMB enabled for (COB|\d+h after carb)|no enableSMB preferences active', errs):
        return 0
    return None


def smb_interval(reason, errs):
    w = re.search(r'Waiting (\d+)m (\d+)s', reason)
    lb = re.search(r'last bolus ([\d.]+)m ago', errs)
    if w and lb:
        return round(float(lb.group(1)) + int(w.group(1)) + int(w.group(2)) / 60)
    return None


def runs(D):
    recs = []
    for f in glob.glob(f"{D}/s_*.json"):
        recs += json.load(open(f))
    out = {}
    for r in recs:
        o = r.get('openaps') or {}
        s = o.get('suggested')
        if not s or 'bg' not in s:
            continue
        reason = s.get('reason', '')
        err = s.get('consoleError') or []
        log = s.get('consoleLog') or []
        errs, logs = '\n'.join(err), '\n'.join(log)
        ts = ms(s['timestamp'])
        basal = num(r'Basal unchanged: ([\d.]+)', logs)
        if basal is None:
            basal = num(r'Adjusting basal from ([\d.]+) to', logs)
        it = o.get('iob') or {}
        if isinstance(it, list):
            it = it[0] if it else {}
        out[ts] = dict(
            ts=ts, bg=s['bg'], target=s.get('targetBG'), eventual=s.get('eventualBG'), insulinReq=s.get('insulinReq'),
            ratio=s.get('sensitivityRatio') or 1.0, dyn=bool(s.get('runningDynamicIsf')), vsens=s.get('variable_sens'),
            cob=s.get('COB'), iob=s.get('IOB'), rate=s.get('rate'), duration=s.get('duration'), units=s.get('units'),
            smb_allowed=not any('!microBolusAllowed' in x for x in err),
            psens=num(r'profile\.sens: ([\d.]+)', errs), cr=num(r'CR:([\d.]+)', errs), basal=basal,
            maxsafe=num(r'maxSafeBasal: ([\d.]+)', reason), reason=reason, err=err, log=log,
            meal_carbs=num(r'COB: [\d.]+ / ([\d.]+)', errs), last_carb_age=num(r'Last carbs (\d+) ?minutes ago', errs),
            uam_seen=uam_state(reason, errs), smb_always_seen=smb_always_state(errs), smb_interval_seen=smb_interval(reason, errs),
            iob_obs=it.get('iob'), basaliob_obs=it.get('basaliob'), activity_obs=it.get('activity'),
        )
    return [out[k] for k in sorted(out)]
