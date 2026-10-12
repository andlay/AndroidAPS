"""Compare rebuilt determine_basal runs with what the phone logged. Usage: python3 -I check_analyze.py <check.tsv>"""
import sys, statistics as st, csv


def f(v):
    return None if v in ('NA', '') else float(v)


rows = list(csv.DictReader(open(sys.argv[1]), delimiter='\t'))
if len(sys.argv) > 2:
    rows = [r for r in rows if int(r['ts']) >= int(sys.argv[2])]
print(f"runs compared: {len(rows)}")


def q(vals, name, unit=''):
    vals = sorted(vals)
    if not vals:
        return
    p = lambda x: vals[min(len(vals) - 1, int(x * len(vals)))]
    print(f"  {name:28s} median {p(.5):7.3f}  p75 {p(.75):7.3f}  p90 {p(.9):7.3f}  {unit}")


bg = [abs(f(r['bg_log']) - f(r['bg_mine'])) for r in rows]
q(bg, '|BG log - mine| mg/dL')
iob = [abs(f(r['iob_log']) - f(r['iob_mine'])) for r in rows if f(r['iob_log']) is not None]
q(iob, '|IOB log - mine| U')
ev = [abs(f(r['ev_log']) - f(r['ev_mine'])) for r in rows if f(r['ev_log']) is not None and f(r['ev_mine']) is not None]
q(ev, '|eventualBG diff| mg/dL')
rq = [abs(f(r['req_log']) - f(r['req_mine'])) for r in rows if f(r['req_log']) is not None and f(r['req_mine']) is not None]
q(rq, '|insulinReq diff| U')

agree_rate = 0
n_rate = 0
for r in rows:
    cur = f(r.get('cur_rate', 'NA'))
    a, b = f(r['rate_log']), f(r['rate_mine'])
    a = cur if a is None else a  # no new temp = keep the running one
    b = cur if b is None else b
    n_rate += 1
    if (a is None and b is None) or (a is not None and b is not None and abs(a - b) <= 0.051):
        agree_rate += 1
print(f"  rate running after the decision same (within 0.05 U/h): {agree_rate / n_rate:.0%}")
both = [(f(r['rate_log']), f(r['rate_mine'])) for r in rows if f(r['rate_log']) is not None and f(r['rate_mine']) is not None]
if both:
    q([abs(a - b) for a, b in both], '|rate diff| when both set', 'U/h')
smb_log = [f(r['smb_log']) or 0.0 for r in rows]
smb_mine = [f(r['smb_mine']) or 0.0 for r in rows]
agree = sum(abs(a - b) <= 0.051 for a, b in zip(smb_log, smb_mine))
print(f"  SMB same (within 0.05 U): {agree / len(rows):.0%};  total SMB log {sum(smb_log):.1f} U vs mine {sum(smb_mine):.1f} U")
print(f"  SMB given: log {sum(a > 0 for a in smb_log)} runs, mine {sum(b > 0 for b in smb_mine)} runs, both {sum(a > 0 and b > 0 for a, b in zip(smb_log, smb_mine))}")
dyn = [(f(r['tdd_backsolved']), f(r['tdd_formula'])) for r in rows if r['dyn'] == '1' and f(r['tdd_backsolved'])]
if dyn:
    ratios = sorted(a / b for a, b in dyn if b)
    print(f"  DynISF runs {len(dyn)}: TDD used by phone / TDD formula from deliveries: median {ratios[len(ratios) // 2]:.3f} "
          f"(p10 {ratios[len(ratios) // 10]:.3f}, p90 {ratios[9 * len(ratios) // 10]:.3f}); median TDD used {st.median(a for a, _ in dyn):.1f} U")
