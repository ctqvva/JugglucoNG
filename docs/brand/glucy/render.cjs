// Render the optional Glucy proposal into this directory only.
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require('playwright');
const root = __dirname;
const master = fs.readFileSync(path.join(__dirname, 'glucy.svg'), 'utf8');
const body = master.slice(master.indexOf('>') + 1, master.lastIndexOf('</svg>')).replace(/<(title|desc)>.*?<\/\1>/gs, '').replace(/^[ \t]+$/gm, '');
const font = fs.readFileSync(path.join(__dirname, '../Outfit.ttf')).toString('base64');
const typography = `<style>@font-face{font-family:Outfit;src:url(data:font/ttf;base64,${font})}text{font-family:Outfit,sans-serif}</style>`;
const svg = (content, box='0 0 108 108') => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${box}" fill="none">${content}</svg>`;
// The master's frame is anchored to the blood drop, not the pen or badge.
const [frameX, frameY, frameWidth, frameHeight] = master.match(/viewBox="([^"]+)"/)[1].split(/\s+/).map(Number);
const placement = `translate(54 54) scale(.058) translate(${-frameX-frameWidth/2} ${-frameY-frameHeight/2})`;
const mark = `<g transform="${placement}">${body}</g>`;
const pen = 'M-36-12H36Q44-12 44-4V29H52V98H58V211H72Q82 211 82 222V426Q82 443 65 443H58V624Q58 642 43 655V684H29V759L20 780H-20L-29 759V684H-44V650Q-58 642-58 624V98H-52V29H-44V-4Q-44-12-36-12Z';
const mono = `<defs><filter id="white"><feFlood flood-color="white"/><feComposite in2="SourceGraphic" operator="in"/></filter><mask id="pen-gap" maskUnits="userSpaceOnUse" x="0" y="0" width="1254" height="1254"><path fill="white" d="M0 0H1254V1254H0Z"/><path transform="translate(1028 500) rotate(40)" d="${pen}" fill="black" stroke="black" stroke-width="34"/></mask><mask id="pen-window" maskUnits="userSpaceOnUse" x="-100" y="-40" width="220" height="860"><path d="${pen}" fill="white"/><rect x="-24" y="216" width="48" height="239" rx="20" fill="black"/></mask></defs><g transform="${placement}"><g mask="url(#pen-gap)"><g filter="url(#white)">${body}</g></g><g transform="translate(1028 500) rotate(40)"><path d="${pen}" fill="white" mask="url(#pen-window)"/></g></g>`;
const badge = '<g transform="translate(12 12.4) scale(.8)"><circle cx="70" cy="72" r="10" fill="#e5b955" stroke="#101a2a" stroke-width="2"/><path d="M67 67H73V77H67ZM65 65H71" stroke="#101a2a" stroke-width="1.8" stroke-linejoin="round"/></g>';
const rawLayers = {
  color: mark,
  duplicate: mark + badge,
  monochrome: mono,
  duplicateMonochrome: mono + '<circle cx="68" cy="70" r="6" fill="white"/>',
};
const centeredLayers = {};
const foreground = duplicate => svg(centeredLayers[duplicate ? 'duplicate' : 'color']);
const icon = (duplicate=false, round=false) => svg(`<${round?'circle cx="54" cy="54" r="53"':'rect x="1" y="1" width="106" height="106" rx="23"'} fill="${duplicate?'#263d3c':'#101a2a'}"/><g transform="translate(-27 -27) scale(1.5)">${centeredLayers[duplicate ? 'duplicate' : 'color']}</g>`);
const banner = svg(`${typography}<rect width="1800" height="600" rx="32" fill="#101a2a"/><g transform="translate(270 -13)"><g transform="translate(-47 -36) scale(.56)">${body}</g><text x="655" y="361" fill="#f2f6fa" font-size="190" font-weight="600">Glucy</text></g>`, '0 0 1800 600');
const variants = () => [['Round launcher',icon(false,true)],['Rounded launcher',icon()],['Second installation',icon(true)],['Themed, light',svg('<rect width="108" height="108" rx="25" fill="#e4e8ef"/><g style="filter:brightness(0) saturate(100%)" transform="translate(-27 -27) scale(1.5)">'+centeredLayers.monochrome+'</g>')],['Themed, dark',svg('<circle cx="54" cy="54" r="54" fill="#24303e"/><g transform="translate(-27 -27) scale(1.5)">'+centeredLayers.monochrome+'</g>')]];
const examples = () => svg(`${typography}<rect width="1600" height="520" rx="28" fill="#101722"/><text x="60" y="82" fill="#f2f6fa" font-size="38" font-weight="500">Glucy on Android</text>${variants().map(([label,art],i)=>`<svg x="${65+i*310}" y="155" width="220" height="220" viewBox="0 0 108 108">${art.slice(art.indexOf('>')+1,art.lastIndexOf('</svg>')).replaceAll(/id="([^"]+)"/g,(_,id)=>`id="v${i}-${id}"`).replaceAll(/url\(#([^)]+)\)/g,(_,id)=>`url(#v${i}-${id})`).replaceAll(/href="#([^"]+)"/g,(_,id)=>`href="#v${i}-${id}"`)}</svg><text x="${175+i*310}" y="435" text-anchor="middle" fill="#c5cfda" font-size="23">${label}</text>`).join('')}`, '0 0 1600 520');
function write(relative, content) {const dest=path.join(root,relative);fs.mkdirSync(path.dirname(dest),{recursive:true});fs.writeFileSync(dest,content);}
(async()=>{
 const browser=await chromium.launch({headless:true});
 try {
  const page=await browser.newPage({deviceScaleFactor:1});
  // Keep the drop on the same axis in every variant. Measure only the radius:
  // including a pen or badge in a centroid would move the drop off its anchor.
  let maximumRadius = 0;
  for (const layer of Object.values(rawLayers)) {
   const radius = await page.evaluate(async art => {
    const image = new Image();
    image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(art)}`;
    await image.decode();
    const canvas = document.createElement('canvas'); canvas.width = canvas.height = 864;
    const context = canvas.getContext('2d'); context.drawImage(image, 0, 0, 864, 864);
    const {data} = context.getImageData(0, 0, 864, 864);
    let radius = 0;
    for (let i = 3; i < data.length; i += 4) if (data[i] >= 128) {
     const pixel = (i - 3) / 4;
     radius = Math.max(radius, Math.hypot((pixel % 864 + .5) / 8 - 54, (Math.floor(pixel / 864) + .5) / 8 - 54));
    }
    return radius;
   }, svg(layer));
   maximumRadius = Math.max(maximumRadius, radius);
  }
  const scale = Math.min(1, 32 / maximumRadius);
  for (const [name, layer] of Object.entries(rawLayers)) {
   centeredLayers[name] = `<g transform="translate(54 54) scale(${scale}) translate(-54 -54)">${layer}</g>`;
  }
  console.log('Shared drop anchor:', [frameX+frameWidth/2, frameY+frameHeight/2], 'safe-circle scale:', scale);
  async function png(relative, art, width, height=width) {
   await page.setViewportSize({width,height});
   await page.setContent(`<style>html,body{margin:0;width:100%;height:100%}body>svg{display:block;width:100%;height:100%}</style>${art}`);
   await page.evaluate(()=>document.fonts.ready);
   const dest=path.join(root,relative);fs.mkdirSync(path.dirname(dest),{recursive:true});
   await page.screenshot({path:dest,omitBackground:true});
  }
  write('icon.svg',icon());
  write('adaptive-foreground.svg',foreground(false));
  write('monochrome.svg',svg(centeredLayers.monochrome));
  write('banner.svg',banner);
  await png('banner.png',banner,1800,600);
  await png('launcher-examples.png',examples(),1600,520);
  await png('icon.png',icon(),512);
  await png('mark.png',master,Math.round(508*frameWidth/frameHeight),508);
  write('duplicate-foreground.svg',foreground(true));
  write('duplicate-monochrome.svg',svg(centeredLayers.duplicateMonochrome));
  write('launcher-examples.svg',examples());
  await png('monochrome.png',svg(centeredLayers.monochrome),432);
  await png('adaptive-foreground.png',foreground(false),432);
  console.log('Rendered Glucy proposal assets; application resources are unchanged.');
 } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1});
