// Regenerate repository artwork and Android launcher resources from glucifer.svg.
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require('playwright');
const root = path.resolve(__dirname, '../..');
const master = fs.readFileSync(path.join(__dirname, 'glucifer.svg'), 'utf8');
const body = master.slice(master.indexOf('>') + 1, master.lastIndexOf('</svg>')).replace(/<(title|desc)>.*?<\/\1>/gs, '');
const font = fs.readFileSync(path.join(__dirname, 'Outfit.ttf')).toString('base64');
const typography = `<style>@font-face{font-family:Outfit;src:url(data:font/ttf;base64,${font})}text{font-family:Outfit,sans-serif}</style>`;
const svg = (content, box='0 0 108 108') => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${box}" fill="none">${content}</svg>`;
// Center the master within Android's 66 dp safe region on a 108 dp layer.
const mark = `<g transform="translate(14.27 17.90) scale(.058)">${body}</g>`;
const pen = 'M-36-12H36Q44-12 44-4V29H52V98H58V211H72Q82 211 82 222V426Q82 443 65 443H58V624Q58 642 43 655V684H29V759L20 780H-20L-29 759V684H-44V650Q-58 642-58 624V98H-52V29H-44V-4Q-44-12-36-12Z';
const mono = `<defs><filter id="white"><feFlood flood-color="white"/><feComposite in2="SourceGraphic" operator="in"/></filter><mask id="pen-gap" maskUnits="userSpaceOnUse" x="0" y="0" width="1254" height="1254"><path fill="white" d="M0 0H1254V1254H0Z"/><path transform="translate(1028 500) rotate(40)" d="${pen}" fill="black" stroke="black" stroke-width="34"/></mask><mask id="pen-window" maskUnits="userSpaceOnUse" x="-100" y="-40" width="220" height="860"><path d="${pen}" fill="white"/><rect x="-24" y="216" width="48" height="239" rx="20" fill="black"/></mask></defs><g transform="translate(14.27 17.90) scale(.058)"><g mask="url(#pen-gap)"><g filter="url(#white)">${body}</g></g><g transform="translate(1028 500) rotate(40)"><path d="${pen}" fill="white" mask="url(#pen-window)"/></g></g>`;
const badge = '<g transform="translate(12 12.4) scale(.8)"><circle cx="70" cy="72" r="10" fill="#e5b955" stroke="#101a2a" stroke-width="2"/><path d="M67 67H73V77H67ZM65 65H71" stroke="#101a2a" stroke-width="1.8" stroke-linejoin="round"/></g>';
const foreground = duplicate => svg(mark + (duplicate ? badge : ''));
const icon = (duplicate=false, round=false) => svg(`<${round?'circle cx="54" cy="54" r="53"':'rect x="1" y="1" width="106" height="106" rx="23"'} fill="${duplicate?'#263d3c':'#101a2a'}"/><g transform="translate(-27 -27) scale(1.5)">${mark}${duplicate?badge:''}</g>`);
const banner = svg(`${typography}<rect width="1800" height="600" rx="32" fill="#101a2a"/><g transform="translate(-47 -36) scale(.56)">${body}</g><text x="655" y="361" fill="#f2f6fa" font-size="190" font-weight="600">Glucifer</text>`, '0 0 1800 600');
const variants = [['Round launcher',icon(false,true)],['Rounded launcher',icon()],['Second installation',icon(true)],['Themed, light',svg('<rect width="108" height="108" rx="25" fill="#e4e8ef"/><g style="filter:brightness(0) saturate(100%)" transform="translate(-27 -27) scale(1.5)">'+mono+'</g>')],['Themed, dark',svg('<circle cx="54" cy="54" r="54" fill="#24303e"/><g transform="translate(-27 -27) scale(1.5)">'+mono+'</g>')]];
const examples = svg(`${typography}<rect width="1600" height="520" rx="28" fill="#101722"/><text x="60" y="82" fill="#f2f6fa" font-size="38" font-weight="500">Glucifer on Android</text>${variants.map(([label,art],i)=>`<svg x="${65+i*310}" y="155" width="220" height="220" viewBox="0 0 108 108">${art.slice(art.indexOf('>')+1,art.lastIndexOf('</svg>')).replaceAll(/id="([^"]+)"/g,(_,id)=>`id="v${i}-${id}"`).replaceAll(/url\(#([^)]+)\)/g,(_,id)=>`url(#v${i}-${id})`).replaceAll(/href="#([^"]+)"/g,(_,id)=>`href="#v${i}-${id}"`)}</svg><text x="${175+i*310}" y="435" text-anchor="middle" fill="#c5cfda" font-size="23">${label}</text>`).join('')}`, '0 0 1600 520');
function write(relative, content) {const dest=path.join(root,relative);fs.mkdirSync(path.dirname(dest),{recursive:true});fs.writeFileSync(dest,content);}
(async()=>{
 const browser=await chromium.launch({headless:true});
 try {
  const page=await browser.newPage({deviceScaleFactor:1});
  async function png(relative, art, width, height=width) {
   await page.setViewportSize({width,height});
   await page.setContent(`<style>html,body{margin:0;width:100%;height:100%}body>svg{display:block;width:100%;height:100%}</style>${art}`);
   await page.evaluate(()=>document.fonts.ready);
   const dest=path.join(root,relative);fs.mkdirSync(path.dirname(dest),{recursive:true});
   await page.screenshot({path:dest,omitBackground:true});
  }
  write('docs/brand/icon.svg',icon());
  write('docs/brand/adaptive-foreground.svg',foreground(false));
  write('docs/brand/monochrome.svg',svg(mono));
  write('docs/brand/banner.svg',banner);
  await png('docs/brand/banner.png',banner,1800,600);
  await png('docs/brand/launcher-examples.png',examples,1600,520);
  await png('docs/brand/icon.png',icon(),512);
  await png('docs/brand/mark.png',master,415,508);
  await png('Common/src/main/ic_launcher-playstore.png',icon(),512);
  for(const [density,factor] of Object.entries({mdpi:1,hdpi:1.5,xhdpi:2,xxhdpi:3,xxxhdpi:4})) {
   for(const source of ['main','releasedub']) {
    const dir=`Common/src/${source}/res/mipmap-${density}`;
    for(const name of ['ic_launcher','ic_launcher_round','ic_launcher_foreground']){
     const old=path.join(root,dir,`${name}.webp`);if(fs.existsSync(old))fs.unlinkSync(old);
    }
    await png(`${dir}/ic_launcher.png`,icon(source==='releasedub'),48*factor);
    await png(`${dir}/ic_launcher_round.png`,icon(source==='releasedub',true),48*factor);
    await png(`${dir}/ic_launcher_foreground.png`,foreground(source==='releasedub'),108*factor);
    await png(`${dir}/ic_launcher_monochrome.png`,svg(mono+(source==='releasedub'?'<circle cx="68" cy="70" r="6" fill="white"/>':'')),108*factor);
   }
  }
  for(const source of ['main','releasedub']) {
   write(`Common/src/${source}/res/drawable/ic_launcher_background.xml`,`<?xml version="1.0" encoding="utf-8"?>\n<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n    <solid android:color="${source==='main'?'#101a2a':'#263d3c'}"/>\n</shape>\n`);
  }
  for(const name of ['ic_launcher','ic_launcher_round'])write(`Common/src/main/res/mipmap-anydpi-v33/${name}.xml`,'<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@drawable/ic_launcher_background"/>\n    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>\n</adaptive-icon>\n');
  console.log('Rendered banner, launcher examples, transparent mark and Android icons.');
 } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1});
