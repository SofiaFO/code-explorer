"use strict";

// Renderiza o circle packing (mesmo HTML/D3 do plugin) fora do IntelliJ,
// via Puppeteer, para o pipeline de CI: lê graph.json + template.html,
// injeta o JSON no placeholder e tira um PNG + PDF do resultado.
//
// Uso: node screenshot.js <graph.json> <commit-hash> <data>

const fs = require("fs");
const path = require("path");
const puppeteer = require("puppeteer");

const PLACEHOLDER = "__GRAPH_DATA__";
const VIEWPORT = { width: 1400, height: 900 };

async function main() {
  const [, , graphJsonPath, commitHash, date] = process.argv;

  if (!graphJsonPath || !commitHash || !date) {
    console.error("Uso: node screenshot.js <graph.json> <commit-hash> <data>");
    process.exit(1);
  }

  const graphJsonAbsPath = path.resolve(graphJsonPath);
  const templatePath = path.resolve(__dirname, "template.html");
  const outputDir = path.resolve(__dirname, "output");

  const graphData = JSON.parse(fs.readFileSync(graphJsonAbsPath, "utf8"));
  const template = fs.readFileSync(templatePath, "utf8");
  const html = template.replace(PLACEHOLDER, JSON.stringify(graphData));

  fs.mkdirSync(outputDir, { recursive: true });

  const pngPath = path.join(outputDir, `visualization_${date}_${commitHash}.png`);
  const pdfPath = path.join(outputDir, `visualization_${date}_${commitHash}.pdf`);

  const browser = await puppeteer.launch({
    headless: true,
    executablePath: process.env.PUPPETEER_EXECUTABLE_PATH || undefined,
    args: ["--no-sandbox", "--disable-setuid-sandbox"]
  });

  try {
    const page = await browser.newPage();
    await page.setViewport(VIEWPORT);
    await page.setContent(html, { waitUntil: "networkidle0" });
    await page.waitForSelector("svg circle");

    await page.screenshot({ path: pngPath });
    await page.pdf({
      path: pdfPath,
      width: `${VIEWPORT.width}px`,
      height: `${VIEWPORT.height}px`,
      printBackground: true
    });

    console.log(`OK: ${pngPath}`);
    console.log(`OK: ${pdfPath}`);
  } finally {
    await browser.close();
  }
}

main().catch(err => {
  console.error("Falha ao gerar screenshot/PDF:", err);
  process.exit(1);
});
