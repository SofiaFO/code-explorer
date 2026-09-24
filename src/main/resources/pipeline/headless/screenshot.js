"use strict";

// Renderiza o circle packing (mesmo HTML/D3 do plugin) fora do IntelliJ,
// via Puppeteer, para o pipeline de CI: lê graph.json + template.html,
// injeta o JSON no placeholder e tira um PNG + PDF do resultado, com um
// painel lateral de métricas (módulo/commit/branch/resumo/top fan-in/fan-out).
//
// Uso: node screenshot.js <graph.json> <commit-hash> <data> [modulo]

const fs = require("fs");
const path = require("path");
const puppeteer = require("puppeteer");

const GRAPH_PLACEHOLDER = "__GRAPH_DATA__";
const META_PLACEHOLDER = "__SNAPSHOT_META__";
const VIEWPORT = { width: 1400, height: 820 };

async function main() {
  const [, , graphJsonPath, commitHash, date, moduleArg] = process.argv;

  if (!graphJsonPath || !commitHash || !date) {
    console.error("Uso: node screenshot.js <graph.json> <commit-hash> <data> [modulo]");
    process.exit(1);
  }

  const graphJsonAbsPath = path.resolve(graphJsonPath);
  const templatePath = path.resolve(__dirname, "template.html");
  const outputDir = path.resolve(__dirname, "output");

  // Sem módulo explícito, deriva do caminho do JSON (ex.: .../ms-auth/output/
  // graph.json -> "ms-auth") em vez de deixar o painel sem nada pra mostrar.
  const moduleName = moduleArg || path.basename(path.resolve(graphJsonAbsPath, "../.."));

  const graphData = JSON.parse(fs.readFileSync(graphJsonAbsPath, "utf8"));
  const template = fs.readFileSync(templatePath, "utf8");

  const meta = {
    module: moduleName,
    date,
    commit: commitHash,
    branch: process.env.GITHUB_HEAD_REF || process.env.BITBUCKET_BRANCH || ""
  };

  const html = template
    .replace(GRAPH_PLACEHOLDER, JSON.stringify(graphData))
    .replace(META_PLACEHOLDER, JSON.stringify(meta));

  fs.mkdirSync(outputDir, { recursive: true });

  const baseName = moduleArg
    ? `visualization_${date}_${commitHash}_${moduleArg}`
    : `visualization_${date}_${commitHash}`;
  const pngPath = path.join(outputDir, `${baseName}.png`);
  const pdfPath = path.join(outputDir, `${baseName}.pdf`);

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
    // O painel lateral é montado via innerHTML por buildMetaPanel() logo
    // depois do circle packing — espera ele ter conteúdo real antes do
    // screenshot, em vez de confiar só no "networkidle0" (que não garante
    // que o <script> síncrono já rodou até o fim em toda situação).
    await page.waitForFunction(
      () => {
        const panel = document.getElementById("meta-panel");
        return !!panel && panel.children.length > 0;
      },
      { timeout: 15000 }
    );
    // Dá mais um tempo pras fontes (Google Fonts) terminarem de carregar.
    await new Promise(r => setTimeout(r, 2000));

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
