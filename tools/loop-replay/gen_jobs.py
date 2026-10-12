"""
Write the sweep scenarios for the loop replay. Usage: python3 -I gen_jobs.py <from ms> <out file>
factor=111 is the effective DynISF factor the check found for the first data set (TDD the phone
used / TDD formula over delivered insulin). Set it from your own check output.
"""
import sys

FROM, OUT = sys.argv[1], sys.argv[2]
jobs = []
for ti in (27, 36, 45):  # true ISF 1.5 / 2.0 / 2.5 mmol/L per U
    c = f"from={FROM} trueIsf={ti} factor=111"
    jobs.append(f"asrun_t{ti} asRun=1 from={FROM} trueIsf={ti}")
    for p in (70, 80, 90, 100, 110, 120, 130):
        jobs.append(f"pct{p}_t{ti} pct={p} {c}")
    for cr in (0.8, 0.9, 1.1, 1.2, 1.3, 1.4):
        jobs.append(f"cr{cr}_t{ti} cr={cr} {c}")
    for isf in (0.7, 0.8, 0.9, 1.1, 1.2, 1.3):
        jobs.append(f"isf{isf}_t{ti} isf={isf} {c}")
    for b in (0.8, 0.9, 1.1, 1.2):
        jobs.append(f"basal{b}_t{ti} basal={b} {c}")
    for cr in (0.9, 1.0, 1.1, 1.2, 1.3):
        for isf in (0.8, 0.9, 1.0, 1.1, 1.2):
            if cr == 1.0 and isf == 1.0:
                continue
            jobs.append(f"grid_cr{cr}_isf{isf}_t{ti} cr={cr} isf={isf} {c}")
    for smb in (0, 108, 126, 162):
        jobs.append(f"smbmin{smb}_t{ti} smbMin={smb} {c}")
    for isf in (0.9, 1.0, 1.1):
        jobs.append(f"nodyn_isf{isf}_t{ti} dyn=0 isf={isf} {c}")
    jobs.append(f"uam_on_t{ti} uam=1 {c}")
    jobs.append(f"smbint5_t{ti} smbInt=5 {c}")
    jobs.append(f"base_t{ti} {c}")
open(OUT, 'w').write('\n'.join(jobs) + '\n')
print(len(jobs), 'jobs')
