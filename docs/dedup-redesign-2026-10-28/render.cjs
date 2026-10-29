const { chromium } = require('C:/Users/10540/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const path = require('path');
const fs = require('fs');
(async () => {
 const browser = await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 const page = await browser.newPage({viewport:{width:2150,height:2020},deviceScaleFactor:1});
 const errors=[]; page.on('pageerror',e=>errors.push(e.message));
 await page.goto('file:///'+path.join(__dirname,'index.html').replaceAll('\\','/'));
 await page.evaluate(()=>document.fonts.ready);
 await page.screenshot({path:path.join(__dirname,'ten-options-light.png'),fullPage:true});
 for(let i=1;i<=10;i++){
   const id=String(i).padStart(2,'0');
   await page.locator('#option-'+id).screenshot({path:path.join(__dirname,'option-'+id+'.png')});
 }
 // Exercise every design rather than assuming its alternate layout has working controls.
 for(let i=1;i<=10;i++){
   const proposal=page.locator('#option-'+String(i).padStart(2,'0'));
   await proposal.locator('[data-entry$="-1"]').first().click();
   await proposal.locator('.move').click();
   if(await proposal.locator('.dialog').count()!==1)throw new Error('Missing confirmation '+i);
   await proposal.locator('[data-close]').click();
 }
 await page.reload();
 await page.locator('#theme').click();
 await page.screenshot({path:path.join(__dirname,'ten-options-dark.png'),fullPage:true});
 await page.locator('#toast-demo').click();
 const toast=page.locator('#option-01 .toast');
 const box=await toast.boundingBox();
 await page.mouse.move(box.x+box.width*.75,box.y+box.height/2);
 await page.mouse.down(); await page.mouse.move(box.x+box.width*.20,box.y+box.height/2,{steps:12}); await page.mouse.up();
 if(!await toast.evaluate(e=>e.classList.contains('hidden')))throw new Error('Left toast swipe failed');
 await page.locator('#toast-demo').click();
 await page.mouse.move(box.x+box.width*.20,box.y+box.height/2);
 await page.mouse.down(); await page.mouse.move(box.x+box.width*.75,box.y+box.height/2,{steps:12}); await page.mouse.up();
 if(!await toast.evaluate(e=>e.classList.contains('hidden')))throw new Error('Right toast swipe failed');
 await page.locator('#theme').click();
 await page.setViewportSize({width:412,height:900});
 await page.locator('#picker').selectOption('02');
 await page.screenshot({path:path.join(__dirname,'mobile-option-02.png'),fullPage:true});
 if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw new Error('Mobile page overflows');
 if(errors.length)throw new Error(errors.join('\n'));
 await page.setViewportSize({width:760,height:1200});
 await page.goto('file:///'+path.join(__dirname,'index.html').replaceAll('\\','/')+'?final=1');
 await page.locator('#option-07').screenshot({path:path.join(__dirname,'final-scheme-light.png')});
 await page.locator('#theme').click();
 await page.locator('#option-07').screenshot({path:path.join(__dirname,'final-scheme-dark.png')});
 fs.writeFileSync(path.join(__dirname,'preview-checks.json'),JSON.stringify({designs:10,selectionAndConfirmation:'PASS on all 10',toastLeftAndRight:'PASS',mobileWidth412:'PASS',browserErrors:errors},null,2));
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
