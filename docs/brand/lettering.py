"""The Glucy wordmark: custom monoline lettering, drawn in code.

Units: cap height 100, y grows downward, cap line y = 0, x-height line y = 30,
baseline y = 100, descender bottom about y = 127.7. Every weight keeps these metrics,
so a lighter weight opens the counters instead of shrinking the letters.

Read this before "tidying" anything. The l, u and y terminals are deliberately
different, and several values are optical corrections that look wrong on paper:

  l   The stem turns right into a short foot (radius 14) and stops level. It ends a stroke.
  u   Keeps its spur, but the junction where the bowl meets the upright is filleted
      (radius 0.6 × stem). Unlike the feet, the spur is a junction detail, not a stroke
      ending. Unfilleted, it leaves a sharp notch that reads as a drawing error at large
      sizes. A shorter spur was tried and keeps the notch.
  y   The descender turns left into a short foot (radius 16, a little larger than the l's,
      because it follows a bowl and a longer stroke) and stops level. The l and y feet
      are a pair: same construction, opposite directions, different radii. Standardising
      the radii or the directions turns the family resemblance into a repeated motif.
  G   Open, with a short bar (0.38 of the radius). Not yet reviewed in detail.
  c   Plain, open by ±42°.

Optical corrections:
  * Feet: the stem eases into the foot through a short transition curve whose curvature
    rises without a bump (≤ 5 % above the arc's). Exact G2 at the stem was tried; a single
    cubic then overshoots by ~17 % and the foot looks like a bent tube. Values: join at 40°
    past vertical; the l transition starts 10 units above the join (handles 2 and 5), the
    y transition 12 units above (handles 3 and 5.5).
  * Feet are thinned through the turn: 0.90 × stem at the join, 0.94 × along the bottom.
    Otherwise black pools in the inside corner. G, u and c keep an even stroke: their
    bottoms are continuous curves with no flat run, so they don't read heavy.
  * Overshoot by roundness: G +1.2, c +1.0, u/y bowls +0.7, l foot +0.6.
  * Weight: 15 on light backgrounds, 14 on dark. Chosen by eye for these colours at
    README sizes; it is not a general "one unit lighter on dark" rule.
  * Spacing: measured first (equal average white across the x-height band, apertures
    counted only partly), then corrected by eye on the whole word: G–l −1 (the G's open
    lower right reads loose next to a stem), l–u +1 (the foot points into the u and
    makes the gap look smaller), c–y +1.5 (the c terminal points straight at the y stem).
    The resulting positions are fixed below per weight; don't recompute them.
"""
import math
from shapely.geometry import LineString, Point, Polygon
from shapely.ops import unary_union

XH = 70.0                     # x-height (y 30 to 100)
DESC = 127.7                  # outer bottom of the y foot
OVERSHOOT = dict(G=1.2, c=1.0, bowl=0.7, foot=0.6)
FOOT_L = dict(r=14.0, join=40.0, rise=10.0, a=2.0, b=5.0)
FOOT_Y = dict(r=16.0, join=40.0, rise=12.0, a=3.0, b=5.5)
TURN_THIN, FOOT_THIN = 0.90, 0.94
SPUR_FILLET = 0.6             # × stem
G_BAR = 0.38                  # × radius
C_OPEN = 42.0                 # degrees either side of horizontal
# left edge of each letter (G l u c y), reviewed by eye; see the module docstring
POSITIONS = {15.0: (0.0, 111.262, 142.669, 225.202, 300.99),
             14.0: (0.0, 111.186, 141.491, 224.024, 299.426)}
LIGHT_WEIGHT, DARK_WEIGHT = 15.0, 14.0
TOL = 0.12                    # max outline error, cap-height units

# --------------------------------------------------------------------- curves ---
def _B(s, t):
    p0, p1, p2, p3 = s; u = 1 - t
    return (u**3*p0[0] + 3*u*u*t*p1[0] + 3*u*t*t*p2[0] + t**3*p3[0],
            u**3*p0[1] + 3*u*u*t*p1[1] + 3*u*t*t*p2[1] + t**3*p3[1])

def _D1(s, t):
    p0, p1, p2, p3 = s; u = 1 - t
    return (3*u*u*(p1[0]-p0[0]) + 6*u*t*(p2[0]-p1[0]) + 3*t*t*(p3[0]-p2[0]),
            3*u*u*(p1[1]-p0[1]) + 6*u*t*(p2[1]-p1[1]) + 3*t*t*(p3[1]-p2[1]))

def _D2(s, t):
    p0, p1, p2, p3 = s; u = 1 - t
    return (6*u*(p2[0]-2*p1[0]+p0[0]) + 6*t*(p3[0]-2*p2[0]+p1[0]),
            6*u*(p2[1]-2*p1[1]+p0[1]) + 6*t*(p3[1]-2*p2[1]+p1[1]))

def _unit(v):
    l = math.hypot(*v); return (v[0]/l, v[1]/l)

def _normal(s, t):
    d = _D1(s, t)
    if math.hypot(*d) < 1e-9: d = (s[3][0]-s[0][0], s[3][1]-s[0][1])
    tx, ty = _unit(d); return (-ty, tx)

def _kappa(s, t):
    d1, d2 = _D1(s, t), _D2(s, t); v = math.hypot(*d1)
    return 0.0 if v < 1e-9 else (d1[0]*d2[1] - d1[1]*d2[0]) / v**3

def _split(s):
    p0, p1, p2, p3 = s
    m = lambda a, b: ((a[0]+b[0])/2, (a[1]+b[1])/2)
    a, b, c = m(p0, p1), m(p1, p2), m(p2, p3); d, e = m(a, b), m(b, c); f = m(d, e)
    return (p0, a, d, f), (f, e, c, p3)

def _line(p, q):
    return (p, (p[0] + (q[0]-p[0])/3, p[1] + (q[1]-p[1])/3), (p[0] + 2*(q[0]-p[0])/3, p[1] + 2*(q[1]-p[1])/3), q)

def _arc(cx, cy, r, a0, a1):
    """Circular arc as cubics; angles in degrees, y down."""
    n = max(1, math.ceil(abs(a1 - a0) / 90 - 1e-9)); out = []
    for i in range(n):
        b0 = math.radians(a0 + (a1-a0)*i/n); b1 = math.radians(a0 + (a1-a0)*(i+1)/n)
        k = 4/3 * math.tan((b1 - b0)/4)
        p0 = (cx + r*math.cos(b0), cy + r*math.sin(b0)); p3 = (cx + r*math.cos(b1), cy + r*math.sin(b1))
        out.append((p0, (p0[0] - k*r*math.sin(b0), p0[1] + k*r*math.cos(b0)),
                    (p3[0] + k*r*math.sin(b1), p3[1] - k*r*math.cos(b1)), p3))
    return out

class _Stroke:
    """A centreline of cubics with a width at every node, turned into a closed outline
    (offset with curvature-corrected handles, split until within TOL, round caps)."""
    K = 0.5522847498

    def __init__(self, segs, widths):
        self.segs, self.widths = segs, widths

    def _offset(self, s, w0, w1, side):
        d0, d3 = side*w0/2, side*w1/2
        n0, n3 = _normal(s, 0), _normal(s, 1); k0, k3 = _kappa(s, 0), _kappa(s, 1)
        q0 = (s[0][0] + n0[0]*d0, s[0][1] + n0[1]*d0); q3 = (s[3][0] + n3[0]*d3, s[3][1] + n3[1]*d3)
        q1 = (q0[0] + (s[1][0]-s[0][0])*(1 - d0*k0), q0[1] + (s[1][1]-s[0][1])*(1 - d0*k0))
        q2 = (q3[0] + (s[2][0]-s[3][0])*(1 - d3*k3), q3[1] + (s[2][1]-s[3][1])*(1 - d3*k3))
        cand = (q0, q1, q2, q3)
        approx = LineString([_B(cand, i/80) for i in range(81)]); err = 0.0
        for i in range(41):
            t = i/40; nx, ny = _normal(s, t); d = side*(w0 + (w1-w0)*t)/2; p = _B(s, t)
            err = max(err, approx.distance(Point(p[0] + nx*d, p[1] + ny*d)))
        if err > TOL:
            a, b = _split(s); wm = (w0 + w1)/2
            return self._offset(a, w0, wm, side) + self._offset(b, wm, w1, side)
        return [cand]

    def _cap(self, c, t, r, frm, to):
        tip = (c[0] + t[0]*r, c[1] + t[1]*r); K = self.K
        return [(frm, (frm[0] + t[0]*K*r, frm[1] + t[1]*K*r), (tip[0] + (frm[0]-c[0])*K, tip[1] + (frm[1]-c[1])*K), tip),
                (tip, (tip[0] + (to[0]-c[0])*K, tip[1] + (to[1]-c[1])*K), (to[0] + t[0]*K*r, to[1] + t[1]*K*r), to)]

    def outline(self):
        L, R = [], []
        for s, w0, w1 in zip(self.segs, self.widths, self.widths[1:]):
            L += self._offset(s, w0, w1, +1); R += self._offset(s, w0, w1, -1)
        R = [(q[3], q[2], q[1], q[0]) for q in reversed(R)]
        end, start = self.segs[-1], self.segs[0]
        te = _unit(_D1(end, 1)); ts = _unit(_D1(start, 0)); ts = (-ts[0], -ts[1])
        return (L + self._cap(end[3], te, self.widths[-1]/2, L[-1][3], R[0][0]) + R
                + self._cap(start[0], ts, self.widths[0]/2, R[-1][3], L[0][0]))

    def d(self):
        segs = self.outline(); s0 = segs[0][0]
        return (f'M{s0[0]:.3f} {s0[1]:.3f}' + ''.join(
            f'C{s[1][0]:.3f} {s[1][1]:.3f} {s[2][0]:.3f} {s[2][1]:.3f} {s[3][0]:.3f} {s[3][1]:.3f}' for s in segs) + 'Z')

# --------------------------------------------------------------------- letters ---
def _arc_pts(cx, cy, r, a0, a1):
    n = max(8, int(abs(a1 - a0)/3))
    return [(cx + r*math.cos(math.radians(a0 + (a1-a0)*i/n)), cy + r*math.sin(math.radians(a0 + (a1-a0)*i/n))) for i in range(n+1)]

def _svg_arc(cx, cy, r, a0, a1):
    p0 = (cx + r*math.cos(math.radians(a0)), cy + r*math.sin(math.radians(a0)))
    p1 = (cx + r*math.cos(math.radians(a1)), cy + r*math.sin(math.radians(a1)))
    return p0, f'A{r:.3f} {r:.3f} 0 {1 if abs(a1-a0) > 180 else 0} {1 if a1 > a0 else 0} {p1[0]:.3f} {p1[1]:.3f}'

def _foot(stem_x, C, f, side, widths, top, h):
    """Stem → transition → arc to the bottom extremum. side +1 turns right (l), −1 left (y)."""
    r, th = f['r'], f['join']
    ang = math.radians(180 - th) if side > 0 else math.radians(th)
    p3 = (C[0] + r*math.cos(ang), C[1] + r*math.sin(ang))
    t3 = (side*math.sin(math.radians(th)), math.cos(math.radians(th)))
    y0 = p3[1] - f['rise']
    trans = ((stem_x, y0), (stem_x, y0 + f['a']), (p3[0] - t3[0]*f['b'], p3[1] - t3[1]*f['b']), p3)
    arc = _arc(C[0], C[1], r, 180 - th, 90) if side > 0 else _arc(C[0], C[1], r, th, 90)
    return _Stroke([_line((stem_x, top), (stem_x, y0)), trans] + arc, widths)

def letters(S):
    """[(name, svg fragment with {ink} placeholder)] in local coordinates, left edge x = 0."""
    h, o = S/2, OVERSHOOT
    w_join, w_bot = TURN_THIN*S, FOOT_THIN*S
    stroke = lambda ds: ('<g fill="none" stroke="{ink}" stroke-width="%s" stroke-linecap="round" stroke-linejoin="round">' % S
                         + ''.join(f'<path d="{d}"/>' for d in ds) + '</g>')
    out = []
    # G
    r = 50 - h + o['G']; cx, cy = 50 + o['G'], 50
    p0, a = _svg_arc(cx, cy, r, -42, -360)
    out.append(('G', stroke([f'M{p0[0]:.3f} {p0[1]:.3f}' + a + f'L{cx + r*G_BAR:.3f} {cy:.3f}'])))
    # l
    bot = 100 - w_bot/2 + o['foot']
    l = _foot(h, (h + FOOT_L['r'], bot - FOOT_L['r']), FOOT_L, +1, [S, S, w_join, w_bot], h, h)
    out.append(('l', f'<path d="{l.d()}" fill="{{ink}}"/>'))
    # u, as a filled shape so the spur junction can be filleted
    ru = (XH - S)/2; L, R = h, h + 2*ru; top = 30 + h; bowl = 100 - h - ru + o['bowl']
    centre = LineString([(L, top), (L, bowl)] + _arc_pts(L + ru, bowl, ru, 180, 0)[1:] + [(R, top)])
    spur = LineString([(R, top), (R, 100 - h)])
    rf = SPUR_FILLET*S
    u = unary_union([centre.buffer(h, quad_segs=24), spur.buffer(h, quad_segs=24)]).buffer(rf, quad_segs=24).buffer(-rf, quad_segs=24)
    ring = list(u.exterior.coords)[:-1]
    out.append(('u', '<path d="M' + 'L'.join(f'{x:.2f} {y:.2f}' for x, y in ring) + 'Z" fill="{ink}"/>'))
    # c
    rc = (XH - S)/2 + o['c']; ccx, ccy = h + rc, 100 - XH/2
    p0, a = _svg_arc(ccx, ccy, rc, -C_OPEN, -360 + C_OPEN)
    out.append(('c', stroke([f'M{p0[0]:.3f} {p0[1]:.3f}' + a])))
    # y
    p0, a = _svg_arc(L + ru, bowl, ru, 180, 0)
    left = stroke([f'M{L:.3f} {top:.3f}L{L:.3f} {bowl:.3f}' + a])
    desc_c = DESC - w_bot/2
    y = _foot(R, (R - FOOT_Y['r'], desc_c - FOOT_Y['r']), FOOT_Y, -1, [S, S, w_join, w_bot], top, h)
    out.append(('y', left + f'<path d="{y.d()}" fill="{{ink}}"/>'))
    return out

def wordmark(S, ink):
    """(svg fragment, advance width) for the whole word at weight S."""
    xs = POSITIONS[S]
    parts = [f'<g transform="translate({x:.3f} 0)">{frag.replace("{ink}", ink)}</g>' for (_, frag), x in zip(letters(S), xs)]
    ru = (XH - S)/2
    return ''.join(parts), xs[-1] + S + 2*ru
