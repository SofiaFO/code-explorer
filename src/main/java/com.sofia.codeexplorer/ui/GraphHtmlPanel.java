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
          #legend i.border-sample { background: #fff; border: 2px solid; }
          #legend i.border-sample.interface { border-color: #9C27B0; border-width: 3px; }
          #legend i.border-sample.abstract { border-color: #2196F3; border-style: dashed; }
          .node-label { font: 10px sans-serif; pointer-events: none; text-anchor: middle; }
          .selected-node { stroke: #1565C0 !important; stroke-width: 4px !important; }
        </style>
        </head>
        <body>
        <div id="app">
          <div id="chart-container">
            <div id="legend">
              <div class="hint">Clique num pacote para dar zoom · clique numa classe para ver detalhes · clique fora para voltar</div>
              <div class="encoding">Cor — nível de acoplamento (CBO):</div>
              <div class="swatches">
                <span><i style="background:#709AB3"></i>Baixo</span>
                <span><i style="background:#653364"></i>Moderado</span>
                <span><i style="background:#C37EA6"></i>Alto</span>
                <span><i style="background:#66002F"></i>Severo</span>
                <span><i class="border-sample interface"></i>Interface</span>
                <span><i class="border-sample abstract"></i>Classe abstrata</span>
              </div>
              <div class="encoding">Tamanho — fan-in (quantas classes dependem desta)</div>
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

          // Cor de pacote: escala sequencial neutra por profundidade (não usa
          // tons de severidade/tipo, que são reservados para as folhas/classes).
          const packageColor = d3.scaleLinear()
              .domain([0, 4])
              .range(["#cfd8dc", "#374C58"])
              .interpolate(d3.interpolateHcl);

          // Fill = sempre nível de acoplamento.
          function couplingFill(level) {
            switch (level) {
              case "BAIXO":    return "#709AB3";
              case "MODERADO": return "#653364";
              case "ALTO":     return "#C37EA6";
              case "SEVERO":   return "#66002F";
              default:         return "#bdc3c7";
            }
          }

          // Stroke = sempre tipo, independente de severidade.
          function typeStroke(type) {
            if (type === "INTERFACE") return { color: "#9C27B0", width: 3, dash: null };
            if (type === "ABSTRACT_CLASS") return { color: "#2196F3", width: 2, dash: "4,2" };
            return { color: "#999", width: 1, dash: null };
          }

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

          const node = g.append("g")
            .selectAll("circle")
            .data(root.descendants().slice(1))
            .join("circle")
              .attr("fill", d => d.children ? packageColor(d.depth) : couplingFill(d.data.couplingLevel))
              .attr("fill-opacity", d => d.children ? 0.85 : 0.9)
              .attr("stroke", d => d.children ? "#37474f" : typeStroke(d.data.type).color)
              .attr("stroke-width", d => d.children ? 1 : typeStroke(d.data.type).width)
              .attr("stroke-dasharray", d => d.children ? null : typeStroke(d.data.type).dash)
              .attr("stroke-opacity", d => d.children ? 0.6 : 0.8)
              .on("mouseover", function () { d3.select(this).attr("stroke-opacity", 1); })
              .on("mouseout", function (event, d) {
                d3.select(this).attr("stroke-opacity", d.children ? 0.4 : 0.8);
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
            node.attr("transform", d => `translate(${(d.x - v[0]) * k},${(d.y - v[1]) * k})`);
            node.attr("r", d => d.r * k);
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
            const byLevel = { BAIXO: 0, MODERADO: 0, ALTO: 0, SEVERO: 0 };
            leaves.forEach(l => { if (byLevel[l.data.couplingLevel] !== undefined) byLevel[l.data.couplingLevel]++; });

            return `
              <h3>Resumo do projeto</h3>
              <div class="stat-line">Classes: <b>${leaves.length}</b></div>
              <div class="stat-line">Pacotes: <b>${packages}</b></div>
              <div class="stat-line">Ciclos: <b>${(data.cycles || []).length}</b></div>
              <div class="stat-line" style="margin-top:6px;"><b>Acoplamento (CBO):</b></div>
              <div class="stat-line">Baixo: <b>${byLevel.BAIXO}</b> · Moderado: <b>${byLevel.MODERADO}</b> · Alto: <b>${byLevel.ALTO}</b> · Severo: <b>${byLevel.SEVERO}</b></div>
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

            return `
              <h3>${cls.name}</h3>
              <div class="qname">${qName}</div>
              <div class="stat-line">Tipo: <b>${TYPE_LABELS[cls.type] || cls.type}</b></div>
              <div class="stat-line">Pacote: <b>${pkg}</b></div>
              <div class="stat-line">Fan-in (tamanho): <b>${cls.fanIn}</b></div>
              <div class="stat-line">Fan-out: <b>${cls.fanOut}</b></div>
              <div class="stat-line">CBO (cor): <b>${cls.cbo}</b> — <b>${cls.couplingLevel}</b></div>
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
