package com.sofia.codeexplorer.ui;

import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JCEFHtmlPanel;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;

// Painel que mostra o circle packing (com painel lateral de detalhes) via D3
// num navegador embutido (JCEF). Se o ambiente não suportar JCEF, cai para
// uma mensagem simples em vez de quebrar a ToolWindow.
public class GraphHtmlPanel extends JPanel {

    private static final String PLACEHOLDER = "__GRAPH_DATA_JSON__";

    private final JCEFHtmlPanel browser;
    private final JLabel fallbackLabel;

    public GraphHtmlPanel() {
        super(new BorderLayout());
        if (JBCefApp.isSupported()) {
            browser = new JCEFHtmlPanel("about:blank");
            fallbackLabel = null;
            add(browser.getComponent(), BorderLayout.CENTER);
        } else {
            browser = null;
            fallbackLabel = new JLabel(
                "JCEF não está disponível neste ambiente — não é possível exibir o grafo.",
                SwingConstants.CENTER);
            add(fallbackLabel, BorderLayout.CENTER);
        }
    }

    public void updateGraph(String json) {
        if (browser == null) {
            fallbackLabel.setText("Análise concluída, mas o grafo não pode ser exibido (JCEF indisponível).");
            return;
        }
        browser.setHtml(HTML_TEMPLATE.replace(PLACEHOLDER, json));
    }

    public void dispose() {
        if (browser != null) browser.dispose();
    }

    private static final String HTML_TEMPLATE = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <script src="https://d3js.org/d3.v7.min.js"></script>
        <style>
          html, body { margin: 0; height: 100%; overflow: hidden; font-family: sans-serif; }
          #app { display: flex; width: 100vw; height: 100vh; }
          #chart-container { flex: 1 1 auto; position: relative; background: #fafafa; min-width: 0; }
          svg { width: 100%; height: 100%; display: block; }
          #resizer { flex: 0 0 6px; cursor: col-resize; background: #ddd; }
          #resizer:hover, #resizer.dragging { background: #90a4ae; }
          #sidebar {
            flex: 0 0 260px; background: #f5f5f5; border-left: 1px solid #ccc;
            overflow-y: auto; padding: 12px; box-sizing: border-box; font-size: 13px; color: #333;
            word-break: break-word;
          }
          #sidebar h3 { margin: 0 0 8px; font-size: 15px; }
          #sidebar .qname { color: #777; font-size: 11px; word-break: break-all; margin-bottom: 10px; }
          #sidebar .stat-line { margin: 3px 0; }
          #sidebar .stat-line b { color: #000; }
          #sidebar ul { margin: 4px 0 10px; padding-left: 18px; }
          #sidebar li { margin: 2px 0; }
          .metric-row { display: flex; align-items: center; gap: 6px; margin: 6px 0; font-size: 12px; }
          .metric-label { color: #666; min-width: 55px; }
          .metric-value { font-weight: 500; min-width: 28px; text-align: right; }
          .metric-bar-bg { flex: 1; height: 6px; background: #e0e0e0; border-radius: 3px; }
          .metric-bar-fill { height: 6px; border-radius: 3px; }
          .metric-max { color: #999; font-size: 11px; min-width: 50px; }
          #legend {
            position: absolute; top: 8px; left: 8px; font-size: 12px; color: #444;
            background: rgba(255,255,255,0.9); padding: 6px 10px; border-radius: 4px;
            pointer-events: none; line-height: 1.6;
          }
          #legend .hint { margin-bottom: 4px; }
          #legend .encoding { margin-bottom: 2px; font-weight: bold; }
          #legend .swatches { margin-bottom: 4px; }
          #legend .swatches span { display: inline-flex; align-items: center; margin-right: 10px; }
          #legend i { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 4px; vertical-align: middle; }
          #legend i.ring-sample { background: #fff; border: 3px solid #FF6B00; }
          #legend .viridis-gradient {
            width: 160px; height: 10px; border-radius: 3px; margin: 2px 0 4px;
            background: linear-gradient(to right, #440154, #31688E, #35B779, #90D743, #FDE725);
          }
          .node-label { font: 10px sans-serif; pointer-events: none; text-anchor: middle; }
          .selected-node { stroke: #1565C0 !important; stroke-width: 4px !important; }
        </style>
        </head>
        <body>
        <div id="app">
          <div id="chart-container">
            <div id="legend">
              <div class="hint">Clique num pacote para dar zoom · clique numa classe para ver detalhes · clique fora para voltar</div>
              <div class="encoding">Cor — fan-out (roxo = baixo · amarelo = alto)</div>
              <div class="viridis-gradient"></div>
              <div class="encoding">Tamanho — linhas de código (LOC)</div>
              <div class="swatches">
                <span><i class="ring-sample"></i>Fan-in (anel = proporção do máximo)</span>
              </div>
            </div>
            <svg id="chart"></svg>
          </div>
          <div id="resizer"></div>
          <div id="sidebar"></div>
        </div>
        <script>
          const GRAPH_DATA = __GRAPH_DATA_JSON__;
        </script>
        <script>
        (function () {
          const data = GRAPH_DATA;
          const width = 928, height = 928;
          const sidebar = document.getElementById("sidebar");

          // Arrastar #resizer ajusta a largura da sidebar (flex-basis),
          // entre um mínimo legível e um máximo que não engula o gráfico.
          (function setupSidebarResize() {
            const resizer = document.getElementById("resizer");
            let dragging = false;

            resizer.addEventListener("mousedown", (event) => {
              dragging = true;
              resizer.classList.add("dragging");
              document.body.style.cursor = "col-resize";
              event.preventDefault();
            });

            window.addEventListener("mousemove", (event) => {
              if (!dragging) return;
              const newWidth = window.innerWidth - event.clientX;
              const clamped = Math.min(Math.max(newWidth, 180), window.innerWidth - 200);
              sidebar.style.flexBasis = clamped + "px";
            });

            window.addEventListener("mouseup", () => {
              if (!dragging) return;
              dragging = false;
              resizer.classList.remove("dragging");
              document.body.style.cursor = "";
            });
          })();

          // Cor de pacote: única, quase branca, independente da profundidade.
          // Serve só de "container" neutro — as cores fortes ficam reservadas
          // pras classes (fan-out), que são a informação real. A separação
          // entre pacotes aninhados vem da borda (stroke), não do preenchimento.
          const PACKAGE_COLOR = "#FAFAFA";

          // Fill das classes = fan-out normalizado pelo máximo do projeto,
          // via escala contínua Viridis — sem limiares arbitrários de
          // severidade. Fan-out baixo cai no roxo escuro, alto no amarelo.
          const colorScale = d3.scaleSequential()
              .domain([0, GRAPH_DATA.maxFanOut || 1])
              .interpolator(d3.interpolateViridis);

          const TYPE_LABELS = {
            CLASS: "Classe",
            INTERFACE: "Interface",
            ABSTRACT_CLASS: "Classe abstrata",
            ENUM: "Enum"
          };

          // Padding generoso, maior nos níveis mais externos: com poucos
          // pacotes de cada lado, um filho único quase preenche o pai todo,
          // deixando uma borda clicável mínima pra dar zoom nele. Mais
          // espaço reservado perto da raiz evita esse problema.
          const root = d3.pack()
              .size([width, height])
              .padding(d => Math.max(10, 32 - d.depth * 6))
            (d3.hierarchy(data)
              .sum(d => d.value || 0)
              .sort((a, b) => (b.value || 0) - (a.value || 0)));

          // Mapa qualifiedName -> nó da hierarquia, usado pelo painel lateral.
          const byQName = new Map(
            root.descendants()
              .filter(d => d.data.qualifiedName)
              .map(d => [d.data.qualifiedName, d])
          );

          let focus = root;
          let view;
          let k = 1;
          let selectedQName = null;

          const svg = d3.select("#chart")
              .attr("viewBox", `-${width / 2} -${height / 2} ${width} ${height}`)
              .style("cursor", "pointer")
              .on("click", (event) => {
                selectedQName = null;
                updateSelectionHighlight();
                renderSidebar();
                zoom(event, root);
              });

          const g = svg.append("g");

          const RING_GAP_MAX = 13;
          const RING_STROKE_WIDTH_MAX = 6;
          const RING_COLOR = "#FF6B00";

          function fanInRatio(d) {
            const max = GRAPH_DATA.maxFanIn || 0;
            return max > 0 ? d.data.fanIn / max : 0;
          }

          // Gap e espessura proporcionais ao raio JÁ NA TELA (d.r * k), com
          // teto fixo — não ao raio bruto do layout. Baseado no raio bruto,
          // um gap/stroke fixo em px "descola" o anel de folhas minúsculas
          // (pacotes com muitas classes geram círculos de poucos px de raio).
          // Baseado no raio de tela, ele também fica proporcional durante o
          // zoom em vez de crescer sem limite conforme k aumenta.
          function ringGap(d) { return Math.min(RING_GAP_MAX, d.r * k * 0.3); }
          function ringStrokeWidth(d) { return Math.min(RING_STROKE_WIDTH_MAX, Math.max(2.5, d.r * k * 0.25)); }

          // Um <g> por nó (pacote ou classe) agrupando círculo + anel, para que
          // ambos acompanhem a mesma translação/raio durante o zoom.
          const nodeGroup = g.append("g")
            .selectAll("g")
            .data(root.descendants().slice(1))
            .join("g");

          const node = nodeGroup.append("circle")
              .attr("fill", d => d.children ? PACKAGE_COLOR : colorScale(d.data.fanOut))
              .attr("fill-opacity", d => d.children ? 0.85 : 0.9)
              // Borda das folhas = a própria cor do fill escurecida — sempre
              // visível em qualquer ponto da escala Viridis, garante contraste
              // mesmo pra círculos minúsculos que sumiriam sem contorno.
              .attr("stroke", d => d.children ? "#37474f" : d3.color(colorScale(d.data.fanOut)).darker(1.5))
              .attr("stroke-width", 1)
              .attr("stroke-opacity", d => d.children ? 0.6 : 1)
              .on("mouseover", function () { d3.select(this).attr("stroke-opacity", 1); })
              .on("mouseout", function (event, d) {
                d3.select(this).attr("stroke-opacity", d.children ? 0.4 : 1);
              })
              .on("click", (event, d) => {
                event.stopPropagation();
                if (!d.children) {
                  selectedQName = (selectedQName === d.data.qualifiedName) ? null : d.data.qualifiedName;
                  updateSelectionHighlight();
                  renderSidebar();
                }
                if (focus !== d) zoom(event, d);
              });

          // Anel externo de fan-in, só nas folhas com fan-in > 0. pathLength=100
          // normaliza o stroke-dasharray para uma escala fixa (0-100), então o
          // arco continua proporcional mesmo quando o raio muda durante o zoom
          // — sem precisar recalcular a circunferência a cada frame.
          const ring = nodeGroup
            .filter(d => !d.children && fanInRatio(d) > 0)
            .append("circle")
              .attr("class", "fanin-ring")
              .attr("fill", "none")
              .attr("stroke", RING_COLOR)
              .attr("stroke-linecap", "round")
              .attr("pointer-events", "none")
              .attr("pathLength", 100)
              .attr("stroke-dasharray", d => `${fanInRatio(d) * 100} ${100 - fanInRatio(d) * 100}`)
              .attr("transform", "rotate(-90)");

          const label = g.append("g")
              .attr("pointer-events", "none")
            .selectAll("text")
            .data(root.descendants())
            .join("text")
              .attr("class", "node-label")
              .style("fill-opacity", d => d.parent === root ? 1 : 0)
              .style("display", d => d.parent === root ? "inline" : "none")
              .text(d => d.data.name);

          zoomTo([root.x, root.y, root.r * 2]);
          renderSidebar();

          function zoomTo(v) {
            k = width / v[2];
            view = v;
            label.attr("transform", d => `translate(${(d.x - v[0]) * k},${(d.y - v[1]) * k})`);
            nodeGroup.attr("transform", d => `translate(${(d.x - v[0]) * k},${(d.y - v[1]) * k})`);
            node.attr("r", d => d.r * k);
            ring.attr("r", d => d.r * k + ringGap(d));
            ring.attr("stroke-width", d => ringStrokeWidth(d));
          }

          function zoom(event, d) {
            focus = d;
            const transition = svg.transition()
                .duration(event.altKey ? 7500 : 750)
                .tween("zoom", () => {
                  const i = d3.interpolateZoom(view, [focus.x, focus.y, focus.r * 2]);
                  return t => zoomTo(i(t));
                });

            label
              .filter(function (d) { return d.parent === focus || this.style.display === "inline"; })
              .transition(transition)
                .style("fill-opacity", d => d.parent === focus ? 1 : 0)
                .on("start", function (d) { if (d.parent === focus) this.style.display = "inline"; })
                .on("end", function (d) { if (d.parent !== focus) this.style.display = "none"; });
          }

          function updateSelectionHighlight() {
            node.classed("selected-node", d => d.data.qualifiedName === selectedQName);
          }

          function lastSegment(qualifiedName) {
            const i = qualifiedName.lastIndexOf(".");
            return i >= 0 ? qualifiedName.substring(i + 1) : qualifiedName;
          }

          function renderSidebar() {
            sidebar.innerHTML = selectedQName ? classPanelHtml(selectedQName) : summaryPanelHtml();
          }

          function summaryPanelHtml() {
            const leaves = root.leaves();
            const packages = root.descendants().filter(d => d.children && d !== root).length;

            return `
              <h3>Resumo do projeto</h3>
              <div class="stat-line">Classes: <b>${leaves.length}</b></div>
              <div class="stat-line">Pacotes: <b>${packages}</b></div>
              <div class="stat-line">Ciclos: <b>${(data.cycles || []).length}</b></div>
              <div class="stat-line" style="margin-top:6px;">Fan-out máximo (cor): <b>${GRAPH_DATA.maxFanOut || 0}</b></div>
              <div class="stat-line">Fan-in máximo (anel): <b>${GRAPH_DATA.maxFanIn || 0}</b></div>
            `;
          }

          function classPanelHtml(qName) {
            const d = byQName.get(qName);
            if (!d) return summaryPanelHtml();
            const cls = d.data;
            const pkg = qName.includes(".") ? qName.slice(0, qName.length - cls.name.length - 1) : "(pacote default)";

            const uses = (data.edges || [])
              .filter(e => e.source === qName)
              .map(e => `<li>${lastSegment(e.target)} (${e.type})</li>`)
              .join("") || "<li>(nenhuma)</li>";

            const usedBy = (data.edges || [])
              .filter(e => e.target === qName)
              .map(e => `<li>${lastSegment(e.source)} (${e.type})</li>`)
              .join("") || "<li>(nenhuma)</li>";

            const maxFanIn  = GRAPH_DATA.maxFanIn  || 1;
            const maxFanOut = GRAPH_DATA.maxFanOut || 1;
            const fanInPct  = maxFanIn  > 0 ? (cls.fanIn  / maxFanIn)  * 100 : 0;
            const fanOutPct = maxFanOut > 0 ? (cls.fanOut / maxFanOut) * 100 : 0;

            return `
              <h3>${cls.name}</h3>
              <div class="qname">${qName}</div>
              <div class="stat-line">Tipo: <b>${TYPE_LABELS[cls.type] || cls.type}</b></div>
              <div class="stat-line">Pacote: <b>${pkg}</b></div>
              <div class="stat-line">Linhas de código (tamanho): <b>${cls.loc}</b></div>
              <div class="metric-row">
                <span class="metric-label">Fan-in</span>
                <span class="metric-value">${cls.fanIn}</span>
                <div class="metric-bar-bg"><div class="metric-bar-fill" style="width: ${fanInPct}%; background: #1565C0;"></div></div>
                <span class="metric-max">máx: ${maxFanIn}</span>
              </div>
              <div class="metric-row">
                <span class="metric-label">Fan-out (cor)</span>
                <span class="metric-value">${cls.fanOut}</span>
                <div class="metric-bar-bg"><div class="metric-bar-fill" style="width: ${fanOutPct}%; background: #E65100;"></div></div>
                <span class="metric-max">máx: ${maxFanOut}</span>
              </div>
              <div class="stat-line">CBO: <b>${cls.cbo}</b></div>
              <div class="stat-line" style="margin-top:8px;"><b>Usa:</b></div>
              <ul>${uses}</ul>
              <div class="stat-line"><b>Usado por:</b></div>
              <ul>${usedBy}</ul>
            `;
          }
        })();
        </script>
        </body>
        </html>
        """;
}
