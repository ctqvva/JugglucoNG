#!/usr/bin/env python3
"""Create a private, self-contained interactive comparison from replay CSV."""
import argparse
import csv
import datetime as dt
import html
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("readings", type=Path)
    parser.add_argument("--sensor", required=True)
    parser.add_argument("--date", type=dt.date.fromisoformat, required=True)
    parser.add_argument("--offset-hours", type=float, required=True)
    parser.add_argument("--start-hour", type=int, default=0)
    parser.add_argument("--end-hour", type=int, default=24)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    tz = dt.timezone(dt.timedelta(hours=args.offset_hours))
    rows = []
    with args.readings.open() as file:
        for row in csv.DictReader(file):
            time = dt.datetime.fromtimestamp(int(row["timestampMs"]) / 1000, tz)
            if row["sensor"] != args.sensor or row["model"] != "factory_assisted" or time.date() != args.date or not args.start_hour <= time.hour < args.end_hour:
                continue
            values = [float(row[k]) for k in ["factoryMmol", "rawProxyMmol", "glucoseMmol", "lower90Mmol", "upper90Mmol"]]
            if not all(v == v and abs(v) < 100 for v in values):
                continue
            rows.append([time.strftime("%H:%M"), int(row["timestampMs"]), *values])
    if not rows:
        raise ValueError("No usable factory-assisted rows in the requested window")
    title = f"{args.sensor} · {args.date} · UTC{args.offset_hours:+g}"
    summary_path = args.summary or args.readings.parent / "summary.json"
    evaluation = ""
    if summary_path.exists():
        summary = json.loads(summary_path.read_text())
        labels = {"factory_assisted": "V2 factory-assisted", "factory_assisted_references": "V2 + earlier references", "reference_only": "V2 reference-only"}
        for sensor, models in summary["models"].items():
            scored = [(name, m) for name, m in models.items() if m.get("mae_mmol")]
            if not scored:
                continue
            evaluation += f'<table><thead><tr><th>{html.escape(sensor)} pre-reference error</th><th>V2 MAE</th><th>Factory MAE on same minutes</th><th>N</th></tr></thead><tbody>'
            for name, model in scored:
                mae = model["mae_mmol"]
                evaluation += f'<tr><td>{labels[name]}</td><td>{mae["adaptive"]:.2f}</td><td>{mae["factory_same_minutes"]:.2f}</td><td>{model["scored_reference_minutes"]}</td></tr>'
            evaluation += '</tbody></table><p><small>Errors in mmol/L. Each prediction is scored before assimilating that reference. Saved user values are unverified as meter/laboratory measurements. Explicit reset boundaries, if supplied, are listed in summary.json.</small></p>'
    document = TEMPLATE.replace("__TITLE__", html.escape(title)).replace("__DATA__", json.dumps(rows, allow_nan=False)).replace("__EVALUATION__", evaluation)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(document)


TEMPLATE = '''<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>
body{font-family:var(--font-sans,system-ui);color:var(--foreground,#ddd);margin:0}h2{font-size:20px;margin:0 0 8px}p{font-size:14px;line-height:1.5;color:var(--muted-foreground,#888);margin:6px 0 14px}.legend{display:flex;gap:16px;flex-wrap:wrap;font-size:13px;margin:12px 0}.legend span:before{content:' ';display:inline-block;width:18px;height:3px;background:var(--c);vertical-align:middle;margin-right:6px}svg{width:100%;height:320px;display:block;touch-action:pan-y;overflow:visible}.readout{font-size:14px;display:flex;gap:16px;flex-wrap:wrap;min-height:40px;align-items:center}label{font-size:13px;display:block;margin:8px 0 12px}table{font-size:13px;width:100%;border-collapse:collapse;margin-top:18px}th,td{text-align:left;padding:10px 6px;border-bottom:1px solid var(--border,#444)}td:nth-child(n+2),th:nth-child(n+2){text-align:right}small{font-size:12px;color:var(--muted-foreground,#888)}input{accent-color:#42ae8e}
</style></head><body>
<h2>AiDex V2 — first replay</h2><p>__TITLE__<br>Experimental factory-assisted model. Factory scale is retained slowly; minute-to-minute glucose follows the raw signal.</p>
<div class="legend"><span style="--c:#999">Factory auto</span><span style="--c:#7caac7">Raw proxy</span><span style="--c:#42ae8e">AiDex V2</span></div>
<svg id="chart" viewBox="0 0 700 320" role="img" aria-label="Interactive glucose comparison in mmol per litre"></svg>
<div class="readout" id="readout" aria-live="polite"></div><label><input id="band" type="checkbox"> Show model 90% interval (clinical coverage unvalidated)</label>
<p>The factory overshoot is reduced. There are no independent references on KUFY, so this establishes a behavioral difference, not improved glucose accuracy.</p>
__EVALUATION__<p><small>Adaptive V2 is an opt-in experimental AiDex Auto mode. This replay did not establish an accuracy gain on the aging sensor.</small></p>
<script>
const data=__DATA__,svg=document.getElementById('chart'),H=320,L=42,R=10,T=12,B=30;let W=700;
const first=data[0][1],last=data.at(-1)[1];let min=2,max=12;
const x=t=>L+(t-first)/(last-first)*(W-L-R),y=v=>T+(max-v)/(max-min)*(H-T-B);
const colors=['#999','#7caac7','#42ae8e'];let active=Math.max(0,data.findIndex(r=>r[0]==='10:34'));
function path(column){return data.map((r,i)=>(i&&r[1]-data[i-1][1]<=90000?'L':'M')+x(r[1]).toFixed(2)+','+y(r[column]).toFixed(2)).join(' ')}
function intervalBands(rows){const runs=[];for(const row of rows){const run=runs.at(-1);if(!run||row[1]-run.at(-1)[1]>90000)runs.push([row]);else run.push(row)}return runs.map(run=>run.map((r,i)=>(i?'L':'M')+x(r[1])+','+y(r[5])).join(' ')+' '+run.slice().reverse().map(r=>'L'+x(r[1])+','+y(r[6])).join(' ')+' Z')}
function draw(){W=Math.max(320,svg.clientWidth);svg.setAttribute('viewBox',`0 0 ${W} ${H}`);const includeBand=document.getElementById('band').checked,values=data.flatMap(r=>r.slice(2,includeBand?7:5));min=Math.max(0,Math.floor(Math.min(...values)/2)*2);max=Math.max(min+4,Math.ceil(Math.max(...values)/2)*2);let content='';for(let g=min;g<=max;g+=2)content+=`<line x1="${L}" x2="${W-R}" y1="${y(g)}" y2="${y(g)}" stroke="var(--border,#444)" opacity=".5"/><text x="${L-10}" y="${y(g)+4}" text-anchor="end" fill="var(--muted-foreground,#888)" font-size="11">${g}</text>`;
data.forEach(r=>{if(r[0].endsWith(':00'))content+=`<text x="${x(r[1])}" y="${H-6}" text-anchor="middle" fill="var(--muted-foreground,#888)" font-size="11">${r[0]}</text>`});
if(document.getElementById('band').checked){for(const d of intervalBands(data))content+=`<path d="${d}" fill="#42ae8e" opacity=".12"/>`}
for(let c=2;c<5;c++)content+=`<path d="${path(c)}" fill="none" stroke="${colors[c-2]}" stroke-width="${c===4?2.4:1.6}"/>`;
const r=data[active];content+=`<line x1="${x(r[1])}" x2="${x(r[1])}" y1="${T}" y2="${H-B}" stroke="var(--foreground,#ddd)" opacity=".5"/>`;for(let c=2;c<5;c++)content+=`<circle cx="${x(r[1])}" cy="${y(r[c])}" r="3.5" fill="${colors[c-2]}"/>`;
svg.innerHTML=content;document.getElementById('readout').innerHTML=`<strong>${r[0]}</strong><span style="color:${colors[0]}">Auto ${r[2].toFixed(2)}</span><span style="color:${colors[1]}">Raw ${r[3].toFixed(2)}</span><strong style="color:${colors[2]}">V2 ${r[4].toFixed(2)} mmol/L</strong>`;
}
svg.addEventListener('pointermove',e=>{const rect=svg.getBoundingClientRect();const px=(e.clientX-rect.left)/rect.width*W;const time=first+(px-L)/(W-L-R)*(last-first);let lo=0,hi=data.length-1;while(lo<hi){const mid=(lo+hi)>>1;if(data[mid][1]<time)lo=mid+1;else hi=mid}active=lo;draw()});document.getElementById('band').addEventListener('change',draw);new ResizeObserver(draw).observe(svg);draw();console.log('Replay chart rows',data.length,'finite',data.every(r=>r.slice(1).every(Number.isFinite)));
</script></body></html>'''


if __name__ == "__main__":
    main()
