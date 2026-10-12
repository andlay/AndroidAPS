"""
Port of AAPS UnscentedKalmanFilterPlugin (plugins/smoothing, commonMain) to Python.

Same constants, same forward UKF with adaptive R, same Huber-like R inflation, same Q inflation on
real trends (2-of-3 same sign > 2 sigma), same RTS backward smoother, same segmenting at gaps.
Input: list of (timestamp_ms, mg/dL) in ASCENDING time order. Output: smoothed mg/dL, same order.
One difference by necessity: the app re-smooths its visible window again and again and keeps the
learned R between calls; here the whole history is one call, and learned R carries over segments.
"""
import math

N = 2
ALPHA, BETA, KAPPA = 0.1, 2.0, 0.0
LAM = ALPHA * ALPHA * (N + KAPPA) - N
GAMMA = math.sqrt(N + LAM)
WM = [LAM / (N + LAM)] + [1.0 / (2.0 * (N + LAM))] * (2 * N)
WC = [LAM / (N + LAM) + (1 - ALPHA * ALPHA + BETA)] + [1.0 / (2.0 * (N + LAM))] * (2 * N)
Q = [1.0, 0.0, 0.0, 0.35]
R_INIT, R_MIN, R_MAX, R_EFF_MAX = 25.0, 16.0, 225.0, 400.0
INNOV_WINDOW = 18
MAX_G_VAR, MAX_RATE_VAR = 400.0, 4.0
MINOR_GAP, MAJOR_GAP, RATE_TAU = 7.0, 60.0, 30.0


def damp(dt):
    return math.exp(-dt / RATE_TAU)


def msqrt(p):
    a, b, d = p[0], (p[1] + p[2]) / 2.0, p[3]
    l11 = math.sqrt(max(a, 1e-9))
    l21 = b / l11
    disc = d - l21 * l21
    if disc < -1e-9:
        return [math.sqrt(max(a, 0.1)), 0.0, 0.0, math.sqrt(max(d, 0.01))]
    return [l11, l21, 0.0, math.sqrt(max(disc, 1e-9))]


def sigma(x, p):
    s = msqrt(p)
    pts = [[x[0], x[1]]]
    plus, minus = [], []
    for i in range(N):
        plus.append([x[0] + GAMMA * s[i * 2], x[1] + GAMMA * s[i * 2 + 1]])
        minus.append([x[0] - GAMMA * s[i * 2], x[1] - GAMMA * s[i * 2 + 1]])
    return pts + plus + minus


def predict(x, p, q, dt):
    sp = sigma(x, p)
    dm = damp(dt)
    spp = [[s[0] + s[1] * dt, s[1] * dm] for s in sp]
    xp = [sum(WM[i] * spp[i][0] for i in range(5)), sum(WM[i] * spp[i][1] for i in range(5))]
    pp = [0.0] * 4
    for i in range(5):
        d0, d1 = spp[i][0] - xp[0], spp[i][1] - xp[1]
        pp[0] += WC[i] * d0 * d0
        pp[1] += WC[i] * d0 * d1
        pp[2] += WC[i] * d1 * d0
        pp[3] += WC[i] * d1 * d1
    qs = dt / 5.0
    pp[0] += q[0] * qs
    pp[3] += q[3] * qs
    pp[0] = max(pp[0], 0.1)
    pp[3] = max(pp[3], 0.001)
    return xp, pp


def update(xp, pp, z, r):
    sp = sigma(xp, pp)
    zs = [s[0] for s in sp]
    zp = sum(WM[i] * zs[i] for i in range(5))
    pzz = sum(WC[i] * (zs[i] - zp) ** 2 for i in range(5)) + r
    if pzz < 1e-6:
        return list(xp), list(pp)
    pxz = [sum(WC[i] * (sp[i][0] - xp[0]) * (zs[i] - zp) for i in range(5)),
           sum(WC[i] * (sp[i][1] - xp[1]) * (zs[i] - zp) for i in range(5))]
    k = [pxz[0] / pzz, pxz[1] / pzz]
    inn = z - zp
    x = [xp[0] + k[0] * inn, min(4.0, max(-4.0, xp[1] + k[1] * inn))]
    p = [pp[0] - k[0] * pzz * k[0], pp[1] - k[0] * pzz * k[1], pp[2] - k[1] * pzz * k[0], pp[3] - k[1] * pzz * k[1]]
    p[0] = max(p[0], 0.1)
    p[3] = max(p[3], 0.001)
    return x, p


def smoother_gain(p, pp, dt):
    dm = damp(dt)
    a00, a01, a10, a11 = p[0] + p[1] * dt, p[1] * dm, p[2] + p[3] * dt, p[3] * dm
    det = pp[0] * pp[3] - pp[1] * pp[2]
    if abs(det) < 1e-10:
        return [0.0] * 4
    i00, i01, i10, i11 = pp[3] / det, -pp[1] / det, -pp[2] / det, pp[0] / det
    return [a00 * i00 + a01 * i10, a00 * i01 + a01 * i11, a10 * i00 + a11 * i10, a10 * i01 + a11 * i11]


class State:
    def __init__(self):
        self.r = R_INIT
        self.innov, self.raw, self.predvar = [], [], []

    def push(self, lst, v):
        lst.insert(0, v)
        del lst[INNOV_WINDOW:]

    def adapt(self, r):
        if len(self.innov) < 12 or not self.predvar:
            return r

        def tmean(v, trim=0.20):
            s = sorted(v)
            k = min(int(len(s) * trim), (len(s) - 1) // 2)
            core = s[k:len(s) - k]
            return sum(core) / len(core)

        n = len(self.innov)
        rhat = min(R_MAX, max(R_MIN, max(R_MIN, tmean(self.raw[:n]) - tmean(self.predvar[:n]))))
        up = rhat > r
        step = r + (0.18 if up else 0.12) * (rhat - r)
        clamped = min(max(step, r * (1.0 if up else 0.90)), r * (1.20 if up else 1.00))
        clamped = min(max(clamped, R_MIN), R_MAX)
        return 0.75 * r + 0.25 * clamped


def smooth_segment(ts, vals, st):
    n = len(vals)
    if n < 2:
        return [max(v, 39.0) for v in vals]
    x = [vals[0], 0.0]
    dt0 = (ts[1] - ts[0]) / 60000.0
    if 3.0 <= dt0 <= 7.0:
        x[1] = max(-4.0, min(4.0, (vals[1] - vals[0]) / dt0))
    p = [16.0, 0.0, 0.0, 1.0]
    r = st.r
    fwd = [0.0] * n
    fwd[0] = x[0]
    states = [None] * n  # states[i]: (x_before, p_before, xPred, pPred, dt) for point i
    signs = []
    for i in range(1, n):
        dt = (ts[i] - ts[i - 1]) / 60000.0
        if MINOR_GAP < dt <= MAJOR_GAP:
            x[1] *= damp(dt)
        p[0] = min(max(p[0], 0.1), MAX_G_VAR)
        p[3] = min(max(p[3], 0.001), MAX_RATE_VAR)
        xpb, ppb = predict(x, p, Q, dt)
        z = vals[i]
        if z <= 38.0:
            states[i] = (list(x), list(p), xpb, ppb, dt)
            x, p = list(xpb), list(ppb)
            fwd[i] = x[0]
            continue
        inn = z - xpb[0]
        normraw = inn / math.sqrt(ppb[0] + r)
        sign = 1 if normraw > 0 else (-1 if normraw < 0 else 0)
        signs.insert(0, sign if abs(normraw) > 2.0 else 0)
        del signs[3:]
        same = 0 if sign == 0 else sum(1 for s in signs if s == sign)
        qinfl = same >= 2
        absn = abs(normraw)
        reff = min(r * (1.0 + max(0.0, absn - 2.0)), min(r + 100.0, R_EFF_MAX))
        qscale = min(max(max(absn, 1.0), 1.0), 3.0) if qinfl else 1.0
        if qscale > 1.0:
            tq = [Q[0] * min(qscale, 2.0), 0.0, 0.0, Q[3] * qscale]
            xpe, ppe = predict(x, p, tq, dt)
        else:
            xpe, ppe = xpb, ppb
        states[i] = (list(x), list(p), xpe, ppe, dt)
        st.push(st.predvar, ppe[0])
        x, p = update(xpe, ppe, z, reff)
        st.push(st.innov, inn * inn / (ppe[0] + reff))
        st.push(st.raw, inn * inn)
        if not (qinfl or absn > 3.0):
            r = st.adapt(r)
        fwd[i] = x[0]
    st.r = r
    out = list(fwd)
    if n >= 3:
        xs = [fwd[n - 1], x[1]]
        for i in range(n - 2, -1, -1):
            xb, pb, xpr, ppr, dt = states[i + 1]
            c = smoother_gain(pb, ppr, dt)
            d0, d1 = xs[0] - xpr[0], xs[1] - xpr[1]
            xs = [fwd[i] + c[0] * d0 + c[1] * d1, xb[1] + c[2] * d0 + c[3] * d1]
            out[i] = xs[0]
    return [max(v, 39.0) for v in out]


def smooth(points):
    """points: [(ts_ms, mgdl)] ascending. Returns smoothed mg/dL list, same order."""
    st = State()
    out = [None] * len(points)
    start = 0
    for i in range(1, len(points) + 1):
        brk = i == len(points)
        if not brk:
            gap = (points[i][0] - points[i - 1][0]) / 60000.0
            brk = not (2.0 <= gap <= MAJOR_GAP) or points[i - 1][1] == 38.0
        if brk:
            seg = points[start:i]
            sm = smooth_segment([p[0] for p in seg], [p[1] for p in seg], st)
            out[start:i] = sm
            start = i
    return out
