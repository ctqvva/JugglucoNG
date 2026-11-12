"""The Glucy wordmark: custom monoline lettering, drawn in code.

Units: cap height 100, y grows downward, cap line y = 0, x-height line y = 30,
baseline y = 100, descender bottom about y = 127.7. Every weight keeps these metrics,
so a lighter weight opens the counters instead of shrinking the letters.

Read this before "tidying" anything. The l, u and y terminals are deliberately
different, and several values are optical corrections that look wrong on paper:

  l   The stem turns right into a short foot (radius 14) and stops level. It ends a stroke.
  u   Keeps its spur, but the junction where the bowl meets the upright is filleted
      (radius 0.6 × stem): an explicit circular arc tangent to the bowl and to the spur's
      round end, so the slight saddle under the u is part of the shape, not an error. Unlike the feet, the spur is a junction detail, not a stroke
      ending. Unfilleted, it leaves a sharp notch that reads as a drawing error at large
      sizes. A shorter spur was tried and keeps the notch. The y has the same junction
      (its bowl meets the descender) and gets the same fillet.
  y   The descender turns left into a short foot (radius 16, a little larger than the l's,
      because it follows a bowl and a longer stroke) and stops level. The l and y feet
      are a pair: same construction, opposite directions, different radii. Standardising
      the radii or the directions turns the family resemblance into a repeated motif.
  G   Open at 42°, bar 0.30 of the radius at full weight. At 0.38 the bar became the letter's
      defining feature; 0.22 turns it into a stub. A thinner bar was tried: on a bar this short
      it either steps where it meets the bowl or reads as a wedge, and looks weak at 128 px.
      The opening stays at 42° because 36° looks cramped and 50° weakens the G, not because
      it matches the c (they differ in size and surrounding space, so equal angles mean little).
      A 4 % wider G was tried and made the first letter too dominant.
  c   Plain, open by ±42°.

Optical corrections:
  * Feet: the stem eases into the foot through a short transition curve whose curvature
    rises without a bump (≤ 5 % above the arc's). Exact G2 at the stem was tried; a single
    cubic then overshoots by ~17 % and the foot looks like a bent tube. Values: join at 40°
    past vertical; the l transition starts 10 units above the join (handles 2 and 5), the
    y transition 12 units above (handles 3 and 5.5).
  * Feet are thinned through the turn: 0.90 × stem at the join, 0.94 × along the bottom.
    The thinning starts 16 units up the straight stem; starting it inside the turn left a
    small kink on the inner edge. It is taken from the inside of the turn only: thinning both
    edges dented the outer edge of the stem, which shows on a long straight line.
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
TAPER = 16.0                  # thinning starts this far above the turn
SPUR_FILLET = 0.6             # × stem
Y_FILLET = 0.45               # × stem, matched by eye to the u; 0.55 runs into the foot's curve
G_BAR = 0.30                  # × radius
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

    def __init__(self, segs, widths, bias=None):
        self.segs, self.widths = segs, widths
        self.bias = bias or [0.0]*len(widths)   # shifts the stroke along its normal per node

    def _offset(self, s, w0, w1, side, b0=0.0, b1=0.0):
        d0, d3 = side*w0/2 + b0, side*w1/2 + b1
        n0, n3 = _normal(s, 0), _normal(s, 1); k0, k3 = _kappa(s, 0), _kappa(s, 1)
        q0 = (s[0][0] + n0[0]*d0, s[0][1] + n0[1]*d0); q3 = (s[3][0] + n3[0]*d3, s[3][1] + n3[1]*d3)
        q1 = (q0[0] + (s[1][0]-s[0][0])*(1 - d0*k0), q0[1] + (s[1][1]-s[0][1])*(1 - d0*k0))
        q2 = (q3[0] + (s[2][0]-s[3][0])*(1 - d3*k3), q3[1] + (s[2][1]-s[3][1])*(1 - d3*k3))
        cand = (q0, q1, q2, q3)
        approx = LineString([_B(cand, i/80) for i in range(81)]); err = 0.0
        for i in range(41):
            t = i/40; nx, ny = _normal(s, t); d = side*(w0 + (w1-w0)*t)/2 + b0 + (b1-b0)*t; p = _B(s, t)
            err = max(err, approx.distance(Point(p[0] + nx*d, p[1] + ny*d)))
        if err > TOL:
            a, b = _split(s); wm, bm = (w0 + w1)/2, (b0 + b1)/2
            return self._offset(a, w0, wm, side, b0, bm) + self._offset(b, wm, w1, side, bm, b1)
        return [cand]

    def _cap(self, c, t, r, frm, to):
        tip = (c[0] + t[0]*r, c[1] + t[1]*r); K = self.K
        return [(frm, (frm[0] + t[0]*K*r, frm[1] + t[1]*K*r), (tip[0] + (frm[0]-c[0])*K, tip[1] + (frm[1]-c[1])*K), tip),
                (tip, (tip[0] + (to[0]-c[0])*K, tip[1] + (to[1]-c[1])*K), (to[0] + t[0]*K*r, to[1] + t[1]*K*r), to)]

    def outline(self):
        L, R = [], []
        b = self.bias
        for i, (s, w0, w1) in enumerate(zip(self.segs, self.widths, self.widths[1:])):
            L += self._offset(s, w0, w1, +1, b[i], b[i+1]); R += self._offset(s, w0, w1, -1, b[i], b[i+1])
        R = [(q[3], q[2], q[1], q[0]) for q in reversed(R)]
        end, start = self.segs[-1], self.segs[0]
        te = _unit(_D1(end, 1)); ts = _unit(_D1(start, 0)); ts = (-ts[0], -ts[1])
        ne, ns = _normal(end, 1), _normal(start, 0)
        ce = (end[3][0] + ne[0]*b[-1], end[3][1] + ne[1]*b[-1]); cs = (start[0][0] + ns[0]*b[0], start[0][1] + ns[1]*b[0])
        return (L + self._cap(ce, te, self.widths[-1]/2, L[-1][3], R[0][0]) + R
                + self._cap(cs, ts, self.widths[0]/2, R[-1][3], L[0][0]))

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

def _fillet(Cb, ro, other, rf, guess=60.0):
    """Concave fillet of radius rf between the bowl's outer circle (centre Cb, radius ro) and the
    shape `other`: a circular arc tangent to both, returned as cubics, plus the junction point J
    where the bowl circle enters `other`. The fillet centre lies on the circle of radius ro + rf
    around Cb, at distance rf from `other`."""
    from shapely.ops import nearest_points
    R, best = ro + rf, None
    for i in range(-900, 901):
        a = math.radians(guess + i*0.05)
        F = (Cb[0] + R*math.cos(a), Cb[1] + R*math.sin(a))
        if other.contains(Point(F)): continue
        err = abs(other.exterior.distance(Point(F)) - rf)
        if best is None or err < best[0]: best = (err, F, a)
    _, F, a = best
    T1 = (Cb[0] + ro*math.cos(a), Cb[1] + ro*math.sin(a))
    T2 = nearest_points(other.exterior, Point(F))[0].coords[0]
    a1 = math.degrees(math.atan2(T1[1]-F[1], T1[0]-F[0])); a2 = math.degrees(math.atan2(T2[1]-F[1], T2[0]-F[0]))
    while a2 - a1 > 180: a2 -= 360
    while a2 - a1 < -180: a2 += 360
    segs = _arc(F[0], F[1], rf, a1, a2)
    inner = other.buffer(-0.3)
    for i in range(1, 3600):                      # walk up the bowl circle to where it enters `other`
        b = a - math.radians(i*0.05)
        J = (Cb[0] + ro*math.cos(b), Cb[1] + ro*math.sin(b))
        if inner.contains(Point(J)): break
    return segs, J

def _fillet_d(segs, J, inset=1.5):
    """The fillet arc is the only visible edge. The patch closes through points `inset` units
    inside the upright (to the right of the fillet's end and of J) and then along a chord of
    the bowl, so none of its hidden sides coincides with an existing edge: two antialiased
    edges on the same line leave a hairline."""
    s0, T2 = segs[0][0], segs[-1][3]
    return (f'M{s0[0]:.3f} {s0[1]:.3f}' + ''.join(
        f'C{s[1][0]:.3f} {s[1][1]:.3f} {s[2][0]:.3f} {s[2][1]:.3f} {s[3][0]:.3f} {s[3][1]:.3f}' for s in segs)
        + f'L{T2[0] + inset:.3f} {T2[1]:.3f}L{J[0] + inset:.3f} {J[1] - inset:.3f}L{J[0]:.3f} {J[1] - inset:.3f}Z')

def _foot(stem_x, C, f, side, widths, top, h):
    """Stem → transition → arc to the bottom extremum. side +1 turns right (l), −1 left (y)."""
    r, th = f['r'], f['join']
    ang = math.radians(180 - th) if side > 0 else math.radians(th)
    p3 = (C[0] + r*math.cos(ang), C[1] + r*math.sin(ang))
    t3 = (side*math.sin(math.radians(th)), math.cos(math.radians(th)))
    y0 = p3[1] - f['rise']
    trans = ((stem_x, y0), (stem_x, y0 + f['a']), (p3[0] - t3[0]*f['b'], p3[1] - t3[1]*f['b']), p3)
    arc = _arc(C[0], C[1], r, 180 - th, 90) if side > 0 else _arc(C[0], C[1], r, th, 90)
    # Start thinning on the straight, TAPER units before the turn: narrowing only inside the
    # short transition puts a visible kink in the inner edge just above the foot.
    S, w_join, w_bot = widths[0], widths[2], widths[3]
    y1 = y0 - TAPER; w_mid = S - 0.4*(S - w_join)
    segs = [_line((stem_x, top), (stem_x, y1)), _line((stem_x, y1), (stem_x, y0)), trans] + arc
    ws = [S, S, w_mid, w_join, w_bot]
    # thin from the inside of the turn only: the outer edge stays where a full-weight stroke
    # would put it, so the silhouette runs unbroken from stem into foot
    return _Stroke(segs, ws, [side*(S - w)/2 for w in ws])

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
    bot = 100 - S/2 + o['foot']
    l = _foot(h, (h + FOOT_L['r'], bot - FOOT_L['r']), FOOT_L, +1, [S, S, w_join, w_bot], h, h)
    out.append(('l', f'<path d="{l.d()}" fill="{{ink}}"/>'))
    # u: strokes, plus an explicit fillet where the bowl meets the spur
    ru = (XH - S)/2; L, R = h, h + 2*ru; top = 30 + h; bowl = 100 - h - ru + o['bowl']
    Cb, ro = (L + ru, bowl), ru + h
    p0, a = _svg_arc(L + ru, bowl, ru, 180, 0)
    bowl_d = f'M{L:.3f} {top:.3f}L{L:.3f} {bowl:.3f}' + a
    spur = LineString([(R, top), (R, 100 - h)]).buffer(h, quad_segs=32)
    fu, Ju = _fillet(Cb, ro, spur, SPUR_FILLET*S)
    out.append(('u', stroke([bowl_d + f'L{R:.3f} {top:.3f}', f'M{R:.3f} {top:.3f}L{R:.3f} {100 - h:.3f}'])
                + f'<path d="{_fillet_d(fu, Ju)}" fill="{{ink}}"/>'))
    # c
    rc = (XH - S)/2 + o['c']; ccx, ccy = h + rc, 100 - XH/2
    p0, a = _svg_arc(ccx, ccy, rc, -C_OPEN, -360 + C_OPEN)
    out.append(('c', stroke([f'M{p0[0]:.3f} {p0[1]:.3f}' + a])))
    # y
    p0, a = _svg_arc(L + ru, bowl, ru, 180, 0)
    left = stroke([f'M{L:.3f} {top:.3f}L{L:.3f} {bowl:.3f}' + a])
    desc_c = DESC - S/2
    y = _foot(R, (R - FOOT_Y['r'], desc_c - FOOT_Y['r']), FOOT_Y, -1, [S, S, w_join, w_bot], top, h)
    # Same junction as the u (bowl meeting an upright that continues down), same method.
    foot_poly = Polygon([_B(sg, i/32) for sg in y.outline() for i in range(32)]).buffer(0)
    # only the descender's stem can host the fillet: a long or upturned terminal must not
    # be mistaken for the other side of the junction
    stem_side = foot_poly.intersection(Polygon([(R - h - 1.5, -1e3), (1e3, -1e3), (1e3, 1e3), (R - h - 1.5, 1e3)]))
    fy, Jy = _fillet(Cb, ro, stem_side, Y_FILLET*S)
    out.append(('y', left + f'<path d="{y.d()}" fill="{{ink}}"/>' + f'<path d="{_fillet_d(fy, Jy)}" fill="{{ink}}"/>'))
    return out

def wordmark(S, ink):
    """(svg fragment, advance width) for the whole word at weight S."""
    xs = POSITIONS[S]
    parts = [f'<g transform="translate({x:.3f} 0)">{frag.replace("{ink}", ink)}</g>' for (_, frag), x in zip(letters(S), xs)]
    ru = (XH - S)/2
    return ''.join(parts), xs[-1] + S + 2*ru
