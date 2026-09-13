// Rebuild all launcher assets from one native vector design.
// Usage: node scripts/generate-icons.cjs (requires sharp on NODE_PATH).
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const root = path.resolve(__dirname, '..');
const res = path.join(root, 'app/src/main/res');
const brand = path.join(root, 'docs/brand');
fs.mkdirSync(brand, { recursive: true });
const folder = 'M28 43Q28 37 34 37H44L50 43H74Q80 43 80 49V70Q80 76 74 76H34Q28 76 28 70Z';
const arrow = 'M41 55H60L55 50L59 46L71 58L59 70L55 66L60 61H41Z';
// Even-odd cutout makes the connection arrow legible in monochrome too.
const mark = `${folder} ${arrow}`;
const svg = (background, shape = '') => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">${shape || (background ? '<rect width="108" height="108" rx="24" fill="#102E3A"/>' : '')}<path fill="#62E4C1" fill-rule="evenodd" d="${mark}"/></svg>`;
const vector = (color, d) => `<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n    <path android:fillColor="${color}" android:fillType="evenOdd" android:pathData="${d}" />\n</vector>\n`;
fs.writeFileSync(path.join(res, 'drawable/ic_launcher_background.xml'), vector('#102E3A', 'M0 0H108V108H0Z'));
fs.writeFileSync(path.join(res, 'drawable/ic_launcher_foreground.xml'), vector('#62E4C1', mark));
fs.writeFileSync(path.join(res, 'drawable/ic_launcher_monochrome.xml'), vector('#FFFFFF', mark));
for (const name of ['ic_launcher', 'ic_launcher_round']) {
    fs.writeFileSync(path.join(res, `mipmap-anydpi/${name}.xml`), `<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@drawable/ic_launcher_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n`);
}
fs.writeFileSync(path.join(brand, 'fileaccess-logo.svg'), svg(true));
fs.writeFileSync(path.join(brand, 'fileaccess-mark.svg'), svg(false));
(async () => {
    await sharp(Buffer.from(svg(true))).resize(512).png().toFile(path.join(brand, 'fileaccess-logo.png'));
    for (const [density, size] of Object.entries({ mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 })) {
        for (const round of [false, true]) {
            const shape = round ? '<circle cx="54" cy="54" r="54" fill="#102E3A"/>' : '';
            await sharp(Buffer.from(svg(true, shape))).resize(size).webp({ lossless: true }).toFile(path.join(res, `mipmap-${density}/ic_launcher${round ? '_round' : ''}.webp`));
        }
    }
    console.log('Generated adaptive vectors, monochrome, 10 launcher WebPs, SVG masters and 512px PNG.');
})();
