package com.sofia.codeexplorer.ui;

import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JCEFHtmlPanel;
import com.intellij.util.ui.StartupUiUtil;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;

// Painel que mostra o circle packing (com painel lateral de detalhes) via D3
// num navegador embutido (JCEF). Se o ambiente não suportar JCEF, cai para
// uma mensagem simples em vez de quebrar a ToolWindow.
public class GraphHtmlPanel extends JPanel {

    private static final String PLACEHOLDER = "__GRAPH_DATA_JSON__";
    private static final String THEME_PLACEHOLDER = "__THEME__";

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
        // Tema é lido no momento do render — reabrir o painel reaplica caso o
        // usuário tenha trocado o tema do IntelliJ nesse meio tempo.
        String theme = StartupUiUtil.INSTANCE.isDarkTheme() ? "dark" : "light";
        String html = HTML_TEMPLATE
            .replace(THEME_PLACEHOLDER, theme)
            .replace(PLACEHOLDER, json);
        browser.setHtml(html);
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
          html, body {
            margin: 0; height: 100%; overflow: hidden; font-family: sans-serif;
            background: var(--bg); color: var(--text);
          }
          #app { display: flex; width: 100vw; height: 100vh; }
          #chart-container { flex: 1 1 auto; position: relative; background: var(--bg); min-width: 0; }
          svg { width: 100%; height: 100%; display: block; }
          #resizer { flex: 0 0 6px; cursor: col-resize; background: var(--divider); }
          #resizer:hover, #resizer.dragging { background: var(--bar-fan-in); }
          #sidebar {
            flex: 0 0 260px; background: var(--panel);
            border-left: 1px solid var(--panel-border);
            box-shadow: -2px 0 8px rgba(0,0,0,0.08);
            overflow-y: auto; padding: 0; box-sizing: border-box; font-size: 13px; color: var(--text);
            word-break: break-word;
          }
          #sidebar h3 { margin: 0 0 8px; font-size: 15px; }
          #sidebar .qname { color: var(--text-secondary); font-size: 11px; word-break: break-all; margin-bottom: 10px; }
          #sidebar .stat-line { margin: 3px 0; }
          #sidebar .stat-line b { color: var(--text); }
          #sidebar ul { margin: 4px 0 10px; padding-left: 18px; }
          #sidebar li { margin: 2px 0; }
          .metric-row { display: flex; align-items: center; gap: 6px; margin: 6px 0; font-size: 12px; }
          .metric-label { color: var(--text-secondary); min-width: 55px; }
          .metric-value {
            font-weight: 500; min-width: 28px; text-align: right;
            font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
          }
          .metric-bar-bg { flex: 1; height: 6px; background: var(--bar-bg); border-radius: 3px; }
          .metric-bar-fill { height: 6px; border-radius: 3px; }
          .metric-max { color: var(--text-secondary); font-size: 11px; min-width: 50px; }
          .rel-count { color: var(--text-secondary); font-size: 11px; }

          .summary-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; padding: 12px; }
          .summary-card {
            background: var(--package-fill); border: 1px solid var(--panel-border);
            border-radius: 8px; padding: 12px 8px; text-align: center;
          }
          .summary-number {
            font-size: 28px; font-weight: 700; color: var(--bar-fan-in);
            font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
          }
          .summary-label { font-size: 11px; color: var(--text-secondary); margin-top: 2px; }
          .highlight-danger .summary-number { color: var(--cycle-line); }

          .class-header {
            background: var(--package-fill); border-bottom: 2px solid var(--bar-fan-in);
            padding: 12px 16px; display: flex; justify-content: space-between; align-items: center;
          }
          .class-name {
            font-size: 18px; font-weight: 700; color: var(--text);
            font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
          }
          .class-type-badge {
            font-size: 10px; font-weight: 600; color: var(--button-text); background: var(--bar-fan-in);
            padding: 2px 8px; border-radius: 10px; letter-spacing: 0.5px; white-space: nowrap;
          }
          .class-body { padding: 12px 16px; }

          .section-divider { height: 1px; background: var(--divider); margin: 10px 0; }
          .section-title {
            font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.8px;
            color: var(--text-secondary); margin-bottom: 6px;
          }

          #legend {
            position: absolute; top: 10px; left: 10px;
            background: var(--legend-bg); border: 1px solid var(--legend-border);
            border-radius: 6px; padding: 8px 10px;
            font-family: 'JetBrains Mono', 'Fira Code', monospace;
            z-index: 10; box-shadow: 0 2px 6px rgba(0,0,0,0.15);
            color: var(--text); pointer-events: none;
          }
          .legend-title {
            font-weight: 600; margin-bottom: 6px; font-size: 10px;
            text-transform: uppercase; letter-spacing: 0.5px; color: var(--text-secondary);
          }
          .legend-row {
            display: flex; align-items: center; height: 18px; gap: 6px; margin-bottom: 4px;
          }
          .legend-row:last-child { margin-bottom: 0; }
          .viridis-bar {
            width: 28px; height: 8px; min-width: 28px; border-radius: 3px;
            background: linear-gradient(to right, #440154, #31688E, #35B779, #90D743, #FDE725);
          }
          .legend-size-icon,
          .legend-ring-icon {
            width: 26px; min-width: 26px; display: flex; align-items: center; justify-content: center;
          }
          .legend-key { font-size: 10px; font-weight: 600; color: var(--text); min-width: 42px; white-space: nowrap; }
          .legend-desc { font-size: 10px; color: var(--text-secondary); white-space: nowrap; }

          #hint {
            position: absolute; bottom: 10px; left: 12px;
            font-family: sans-serif; font-size: 10px; color: var(--text-secondary);
            opacity: 0.65; pointer-events: none; z-index: 10;
          }

          .node-label { font: 10px sans-serif; pointer-events: none; text-anchor: middle; fill: var(--text); }
          .selected-node { stroke: var(--bar-fan-in) !important; stroke-width: 4px !important; }
        </style>
        </head>
        <body>
        <div id="app">
          <div id="chart-container">
            <div id="legend">
              <div class="legend-title">Legenda</div>
              <div class="legend-row">
                <div class="viridis-bar"></div>
                <span class="legend-key">Cor</span><span class="legend-desc">Fan-out</span>
              </div>
              <div class="legend-row">
                <div class="legend-size-icon">
                  <svg width="26" height="14" viewBox="0 0 26 14">
                    <circle cx="5"  cy="7" r="3" fill="#35B779"/>
                    <circle cx="19" cy="7" r="5.5" fill="#35B779"/>
                  </svg>
                </div>
                <span class="legend-key">Tamanho</span><span class="legend-desc">LOC</span>
              </div>
              <div class="legend-row">
                <div class="legend-ring-icon">
                  <svg width="18" height="18" viewBox="0 0 18 18">
                    <circle cx="9" cy="9" r="4.5" fill="#35B779"/>
                    <circle cx="9" cy="9" r="7" fill="none"
                            stroke="#FF6B00" stroke-width="2"
                            stroke-dasharray="28 44" stroke-linecap="round"
                            transform="rotate(-90 9 9)"/>
                  </svg>
                </div>
                <span class="legend-key">Anel</span><span class="legend-desc">Fan-in</span>
              </div>
            </div>
            <div id="hint">Clique num pacote para zoom · clique numa classe para detalhes · clique fora para voltar</div>
            <svg id="chart"></svg>
          </div>
          <div id="resizer"></div>
          <div id="sidebar"></div>
        </div>
        <script>
          const THEME = '__THEME__';
          const GRAPH_DATA = __GRAPH_DATA_JSON__;

          // Paleta de tokens de tema; a paleta Viridis dos círculos de classe
          // e o anel de fan-in (laranja) não dependem do tema, ficam fixos.
          const T = {
            dark: {
              bg:            '#2B2D30',
              packageFill:   '#3C3F41',
              packageStroke: '#6B6B6B',
              text:          '#BCBEC4',
              textSecondary: '#888A8C',
              panel:         '#1E1F22',
              panelBorder:   '#3C3F41',
              legendBg:      '#2B2D30',
              legendBorder:  '#3C3F41',
              barBg:         '#3C3F41',
              barFanIn:      '#4A9EFF',
              barFanOut:     '#FF8C42',
              headerBg:      '#1E1F22',
              headerBorder:  '#3C3F41',
              buttonBg:      '#4A9EFF',
              buttonText:    '#FFFFFF',
              divider:       '#3C3F41',
              cycleLine:     '#FF5555',
            },
            light: {
              bg:            '#FAFAFA',
              packageFill:   '#F0F2F4',
              packageStroke: '#AAAAAA',
              text:          '#1A1A1A',
              textSecondary: '#666666',
              panel:         '#F7F7F7',
              panelBorder:   '#DDDDDD',
              legendBg:      '#FFFFFF',
              legendBorder:  '#DDDDDD',
              barBg:         '#E0E0E0',
              barFanIn:      '#1565C0',
              barFanOut:     '#E65100',
              headerBg:      '#F0F2F4',
              headerBorder:  '#DDDDDD',
              buttonBg:      '#1565C0',
              buttonText:    '#FFFFFF',
              divider:       '#DDDDDD',
              cycleLine:     '#E53935',
            }
          }[THEME];

          (function applyTheme() {
            const rootStyle = document.documentElement.style;
            for (const [key, value] of Object.entries(T)) {
              rootStyle.setProperty('--' + key.replace(/[A-Z]/g, c => '-' + c.toLowerCase()), value);
            }
          })();
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

          // Cor de pacote: única, quase neutra, independente da profundidade.
          // Serve só de "container" — as cores fortes ficam reservadas pras
          // classes (fan-out), que são a informação real. A separação entre
          // pacotes aninhados vem da borda (stroke), não do preenchimento.
          const PACKAGE_FILL = T.packageFill;
          const PACKAGE_STROKE = T.packageStroke;

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

          const RING_STROKE_WIDTH_MAX = 6;
          const RING_COLOR = "#FF6B00";

          function fanInRatio(d) {
            const max = GRAPH_DATA.maxFanIn || 0;
            return max > 0 ? d.data.fanIn / max : 0;
          }

          // Espessura proporcional ao raio JÁ NA TELA (d.r * k), com teto fixo —
          // não ao raio bruto do layout. Baseado no raio bruto, um stroke fixo
          // em px "descola" o anel de folhas minúsculas (pacotes com muitas
          // classes geram círculos de poucos px de raio). Baseado no raio de
          // tela, ele também fica proporcional durante o zoom em vez de crescer
          // sem limite conforme k aumenta.
          function ringStrokeWidth(d) { return Math.min(RING_STROKE_WIDTH_MAX, Math.max(2.5, d.r * k * 0.25)); }

          // Um <g> por nó (pacote ou classe) agrupando círculo + anel, para que
          // ambos acompanhem a mesma translação/raio durante o zoom.
          const nodeGroup = g.append("g")
            .selectAll("g")
            .data(root.descendants().slice(1))
            .join("g");

          const node = nodeGroup.append("circle")
              .attr("fill", d => d.children ? PACKAGE_FILL : colorScale(d.data.fanOut))
              .attr("fill-opacity", d => d.children ? 0.85 : 0.9)
              // Borda das folhas = a própria cor do fill escurecida — sempre
              // visível em qualquer ponto da escala Viridis, garante contraste
              // mesmo pra círculos minúsculos que sumiriam sem contorno.
              .attr("stroke", d => d.children ? PACKAGE_STROKE : d3.color(colorScale(d.data.fanOut)).darker(1.5))
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

          // Anel de fan-in (por dentro do círculo), só nas folhas com fan-in > 0.
          // pathLength=100 normaliza o stroke-dasharray para uma escala fixa
          // (0-100), então o arco continua proporcional mesmo quando o raio
          // muda durante o zoom — sem precisar recalcular a circunferência a
          // cada frame.
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
            ring.attr("r", d => d.r * k);
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
            const cycles = (data.cycles || []).length;

            return `
              <div class="summary-grid">
                <div class="summary-card">
                  <div class="summary-number">${leaves.length}</div>
                  <div class="summary-label">Classes</div>
                </div>
                <div class="summary-card">
                  <div class="summary-number">${packages}</div>
                  <div class="summary-label">Pacotes</div>
                </div>
                <div class="summary-card${cycles > 0 ? " highlight-danger" : ""}">
                  <div class="summary-number">${cycles}</div>
                  <div class="summary-label">Ciclos</div>
                </div>
                <div class="summary-card">
                  <div class="summary-number">${GRAPH_DATA.maxFanOut || 0}</div>
                  <div class="summary-label">Fan-out máx.</div>
                </div>
                <div class="summary-card">
                  <div class="summary-number">${GRAPH_DATA.maxFanIn || 0}</div>
                  <div class="summary-label">Fan-in máx.</div>
                </div>
              </div>
            `;
          }

          // Agrupa arestas repetidas (mesmo alvo/origem + mesmo tipo), já que
          // uma classe pode chamar/usar a mesma outra classe várias vezes no
          // código — sem isso a lista repete a mesma linha uma vez por
          // ocorrência.
          function groupRelations(names) {
            const order = [];
            const byKey = new Map();
            for (const name of names) {
              const entry = byKey.get(name);
              if (entry) entry.count++;
              else { const e = { name, count: 1 }; byKey.set(name, e); order.push(e); }
            }
            return order
              .map(({ name, count }) => `<li>${name}${count > 1 ? ` <span class="rel-count">×${count}</span>` : ""}</li>`)
              .join("") || "<li>(nenhuma)</li>";
          }

          function classPanelHtml(qName) {
            const d = byQName.get(qName);
            if (!d) return summaryPanelHtml();
            const cls = d.data;
            const pkg = qName.includes(".") ? qName.slice(0, qName.length - cls.name.length - 1) : "(pacote default)";

            const uses = groupRelations(
              (data.edges || [])
                .filter(e => e.source === qName)
                .map(e => `${lastSegment(e.target)} (${e.type})`)
            );

            const usedBy = groupRelations(
              (data.edges || [])
                .filter(e => e.target === qName)
                .map(e => `${lastSegment(e.source)} (${e.type})`)
            );

            const maxFanIn  = GRAPH_DATA.maxFanIn  || 1;
            const maxFanOut = GRAPH_DATA.maxFanOut || 1;
            const fanInPct  = maxFanIn  > 0 ? (cls.fanIn  / maxFanIn)  * 100 : 0;
            const fanOutPct = maxFanOut > 0 ? (cls.fanOut / maxFanOut) * 100 : 0;

            return `
              <div class="class-header">
                <div class="class-name">${cls.name}</div>
                <div class="class-type-badge">${(TYPE_LABELS[cls.type] || cls.type).toUpperCase()}</div>
              </div>
              <div class="class-body">
                <div class="qname">${qName}</div>
                <div class="stat-line">Pacote: <b>${pkg}</b></div>
                <div class="stat-line">Linhas de código (tamanho): <b>${cls.loc}</b></div>
                <div class="metric-row">
                  <span class="metric-label">Fan-in</span>
                  <span class="metric-value">${cls.fanIn}</span>
                  <div class="metric-bar-bg"><div class="metric-bar-fill" style="width: ${fanInPct}%; background: var(--bar-fan-in);"></div></div>
                  <span class="metric-max">máx: ${maxFanIn}</span>
                </div>
                <div class="metric-row">
                  <span class="metric-label">Fan-out (cor)</span>
                  <span class="metric-value">${cls.fanOut}</span>
                  <div class="metric-bar-bg"><div class="metric-bar-fill" style="width: ${fanOutPct}%; background: var(--bar-fan-out);"></div></div>
                  <span class="metric-max">máx: ${maxFanOut}</span>
                </div>
                <div class="stat-line">CBO: <b>${cls.cbo}</b></div>
                <div class="section-divider"></div>
                <div class="section-title">Usa</div>
                <ul>${uses}</ul>
                <div class="section-divider"></div>
                <div class="section-title">Usado por</div>
                <ul>${usedBy}</ul>
              </div>
            `;
          }
        })();
        </script>
        </body>
        </html>
        """;
}
