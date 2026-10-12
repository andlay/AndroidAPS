"""
Write the sweep scenarios for the loop replay. Usage: python3 -I gen_jobs.py <from ms> <out file>

Two modes, both at three true ISF values:
- relative (asRun=1 plus scales): your logged settings at each loop run, changed by a scale. Profile
  switches you made (gym, illness) stay. This is the fair test; compare with rel_base.
- fixed: the candidate profile from the input (latest Nightscout profile) on every day.
factor=111 is the effective DynISF factor the check found for the first data set (TDD the phone
used / TDD formula over delivered insulin). Set it from your own check output.
"""
import sys

FROM, OUT = sys.argv[1], sys.argv[2]
jobs = []
for ti in (27, 36, 45):  # true ISF 1.5 / 2.0 / 2.5 mmol/L per U
    rel = f"asRun=1 from={FROM} trueIsf={ti}"
    jobs.append(f"rel_base_t{ti} {rel}" + (" trace=1" if ti == 36 else ""))
    for p in (70, 80, 90, 110, 120):
        jobs.append(f"rel_pct{p}_t{ti} pct={p} {rel}")
    for cr in (0.8, 0.9, 1.1, 1.2, 1.3, 1.4):
        jobs.append(f"rel_cr{cr}_t{ti} cr={cr} {rel}")
    for isf in (0.7, 0.8, 0.9, 1.1, 1.2, 1.3):
        jobs.append(f"rel_isf{isf}_t{ti} isf={isf} {rel}")
    for b in (0.8, 0.9, 1.1, 1.2):
        jobs.append(f"rel_basal{b}_t{ti} basal={b} {rel}")
    for cr in (0.9, 1.0, 1.1, 1.2, 1.3):
        for isf in (0.8, 0.9, 1.0, 1.1, 1.2):
            if cr == 1.0 and isf == 1.0:
                continue
            jobs.append(f"rel_grid_cr{cr}_isf{isf}_t{ti} cr={cr} isf={isf} {rel}")
    # pairs of 10% steps, as the AAPS profile tuning guide suggests changing about 10% at a time
    jobs.append(f"rel_combo_b0.9_cr1.1_t{ti} basal=0.9 cr=1.1 {rel}")
    jobs.append(f"rel_combo_b0.9_isf1.1_t{ti} basal=0.9 isf=1.1 {rel}")
    jobs.append(f"rel_combo_b0.9_cr1.1_isf1.1_t{ti} basal=0.9 cr=1.1 isf=1.1 {rel}")

    fixed = f"from={FROM} trueIsf={ti} factor=111"
    jobs.append(f"base_t{ti} {fixed}" + (" trace=1" if ti == 36 else ""))
    for p in (70, 80, 90, 110, 120, 130):
        jobs.append(f"pct{p}_t{ti} pct={p} {fixed}")
    for cr in (0.8, 0.9, 1.1, 1.2, 1.3, 1.4):
        jobs.append(f"cr{cr}_t{ti} cr={cr} {fixed}")
    for isf in (0.7, 0.8, 0.9, 1.1, 1.2, 1.3):
        jobs.append(f"isf{isf}_t{ti} isf={isf} {fixed}")
    for b in (0.8, 0.9, 1.1, 1.2):
        jobs.append(f"basal{b}_t{ti} basal={b} {fixed}")
    for smb in (0, 108, 126, 162):
        jobs.append(f"smbmin{smb}_t{ti} smbMin={smb} {fixed}")
    for isf in (0.9, 1.0, 1.1):
        jobs.append(f"nodyn_isf{isf}_t{ti} dyn=0 isf={isf} {fixed}")
    jobs.append(f"uam_on_t{ti} uam=1 {fixed}")
    jobs.append(f"smbint5_t{ti} smbInt=5 {fixed}")
open(OUT, 'w').write('\n'.join(jobs) + '\n')
print(len(jobs), 'jobs')
