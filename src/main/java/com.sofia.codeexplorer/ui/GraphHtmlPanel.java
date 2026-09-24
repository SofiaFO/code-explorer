package com.sofia.codeexplorer.ui;

import com.intellij.ide.BrowserUtil;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.ui.jcef.JCEFHtmlPanel;
import org.jetbrains.annotations.Nullable;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

// Painel que mostra a barra superior (título + controles) e o circle packing
// (com painel lateral de detalhes) via D3 num navegador embutido (JCEF). Se o
// ambiente não suportar JCEF, cai para uma mensagem simples em vez de quebrar
// a ToolWindow. O bridge JS->Java (analyzeQuery) é o único canal pelo qual o
// botão "Analisar" dentro do HTML dispara análise real do lado Java.
public class GraphHtmlPanel extends JPanel {

    private static final String GRAPH_DATA_PLACEHOLDER = "__GRAPH_DATA_JSON__";
    private static final String INIT_DATA_PLACEHOLDER = "__INIT_DATA_JSON__";
    private static final String ANALYZE_QUERY_PLACEHOLDER = "__ANALYZE_QUERY_JS__";
    private static final String OPEN_FILE_QUERY_PLACEHOLDER = "__OPEN_FILE_QUERY_JS__";

    private final JCEFHtmlPanel browser;
    private final JLabel fallbackLabel;
    private final JBCefJSQuery analyzeQuery;
    private final JBCefJSQuery openFileQuery;
    private AnalyzeListener analyzeListener;
    private OpenFileListener openFileListener;

    public interface AnalyzeListener {
        void onAnalyzeRequested(@Nullable String moduleName, boolean useCache);
    }

    // Acionado pelo botão "Abrir no editor ↗" do inspetor lateral (Parte 2).
    public interface OpenFileListener {
        void onOpenFileRequested(String qualifiedName);
    }

    public record RenderState(
        String projectName,
        List<String> moduleNames,
        @Nullable String selectedModule,
        boolean useCache,
        @Nullable Long lastAnalyzedAtMillis,
        @Nullable String statusMessage,
        @Nullable String graphJson
    ) {}

    public GraphHtmlPanel() {
        super(new BorderLayout());
        if (isJcefSupported()) {
            browser = new JCEFHtmlPanel("about:blank");
            fallbackLabel = null;
            analyzeQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
            analyzeQuery.addHandler(request -> {
                String[] parts = request.split("\\|", -1);
                String moduleName = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : null;
                boolean useCache = parts.length > 1 && "1".equals(parts[1]);
                // O handler roda numa thread do CEF; a análise (ReadAction,
                // atualização do próprio browser) precisa ser disparada da EDT.
                SwingUtilities.invokeLater(() -> {
                    if (analyzeListener != null) {
                        analyzeListener.onAnalyzeRequested(moduleName, useCache);
                    }
                });
                return new JBCefJSQuery.Response("ok");
            });
            openFileQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
            openFileQuery.addHandler(request -> {
                SwingUtilities.invokeLater(() -> {
                    if (openFileListener != null) {
                        openFileListener.onOpenFileRequested(request);
                    }
                });
                return new JBCefJSQuery.Response("ok");
            });
            add(browser.getComponent(), BorderLayout.CENTER);
        } else {
            browser = null;
            analyzeQuery = null;
            openFileQuery = null;
            fallbackLabel = new JLabel(
                "JCEF não está disponível neste ambiente — o grafo será aberto no navegador padrão do sistema.",
                SwingConstants.CENTER);
            add(fallbackLabel, BorderLayout.CENTER);
        }
    }

    // JBCefApp.isSupported() já cobre "JCEF existe mas está desabilitado",
    // mas em ambientes sem JCEF no runtime da IDE (ex.: builds via snap sem
    // o JBR completo) a própria classe não existe, e a chamada estoura
    // NoClassDefFoundError em vez de retornar false. Capturamos Throwable
    // aqui pra sempre cair no fallback em vez de derrubar a ToolWindow.
    private static boolean isJcefSupported() {
        try {
            return JBCefApp.isSupported();
        } catch (Throwable t) {
            return false;
        }
    }

    public void setAnalyzeListener(AnalyzeListener listener) {
        this.analyzeListener = listener;
    }

    public void setOpenFileListener(OpenFileListener listener) {
        this.openFileListener = listener;
    }

    public void render(RenderState state) {
        // Visual é sempre claro (ver CSS do HTML_TEMPLATE) — não detecta nem
        // injeta tema do IntelliJ. Tema claro é mais legível pra visualização
        // de dados, e manter dois temas não trazia benefício real aqui.
        String analyzeQueryJs = analyzeQuery != null
            ? analyzeQuery.inject("payload")
            : "console.warn('CodeExplorer: bridge JCEF indisponível, Analisar não funciona no fallback.');";
        String openFileQueryJs = openFileQuery != null
            ? openFileQuery.inject("payload")
            : "console.warn('CodeExplorer: bridge JCEF indisponível, abrir no editor não funciona no fallback.');";

        String html = HTML_TEMPLATE
            .replace(INIT_DATA_PLACEHOLDER, buildInitDataJson(state))
            .replace(GRAPH_DATA_PLACEHOLDER, state.graphJson() != null ? state.graphJson() : "null")
            .replace(ANALYZE_QUERY_PLACEHOLDER, analyzeQueryJs)
            .replace(OPEN_FILE_QUERY_PLACEHOLDER, openFileQueryJs);

        if (browser != null) {
            browser.setHtml(html);
            return;
        }

        // Sem JCEF, não há navegador embutido pra usar — abrimos o mesmo
        // HTML no navegador padrão do sistema em vez de deixar o grafo
        // indisponível. Funciona em qualquer máquina com um navegador
        // instalado, independente do runtime da IDE ter JCEF ou não. O
        // botão Analisar não funciona nesse modo (sem bridge JS->Java).
        try {
            File file = File.createTempFile("code-explorer-graph-", ".html");
            file.deleteOnExit();
            Files.writeString(file.toPath(), html, StandardCharsets.UTF_8);
            fallbackLabel.setText("Grafo aberto no navegador padrão do sistema.");
            BrowserUtil.browse(file);
        } catch (IOException e) {
            fallbackLabel.setText("Não foi possível abrir o grafo: " + e.getMessage());
        }
    }

    public void dispose() {
        if (analyzeQuery != null) analyzeQuery.dispose();
        if (openFileQuery != null) openFileQuery.dispose();
        if (browser != null) browser.dispose();
    }

    private static String buildInitDataJson(RenderState s) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"projectName\": \"").append(esc(s.projectName())).append("\",\n");
        sb.append("  \"modules\": [");
        List<String> modules = s.moduleNames();
        for (int i = 0; i < modules.size(); i++) {
            sb.append('"').append(esc(modules.get(i))).append('"');
            if (i < modules.size() - 1) sb.append(", ");
        }
        sb.append("],\n");
        sb.append("  \"selectedModule\": ")
          .append(s.selectedModule() != null ? "\"" + esc(s.selectedModule()) + "\"" : "null").append(",\n");
        sb.append("  \"useCache\": ").append(s.useCache()).append(",\n");
        sb.append("  \"lastAnalyzedAt\": ")
          .append(s.lastAnalyzedAtMillis() != null ? s.lastAnalyzedAtMillis() : "null").append(",\n");
        sb.append("  \"statusMessage\": ")
          .append(s.statusMessage() != null ? "\"" + esc(s.statusMessage()) + "\"" : "null").append("\n");
        sb.append("}");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // O template inteiro como um texto block só ultrapassa o limite de 64KB
    // por constante do arquivo .class (JVM), depois de tudo que foi
    // acrescentado nas Partes 2–4. Dividido em duas metades, cada uma abaixo
    // do limite, e combinado via método (não expressão constante) pra evitar
    // que o javac tente dobrar as duas de volta numa constante só.
    //
    // HTML_TEMPLATE fica declarado só DEPOIS de HTML_TEMPLATE_HEAD/BODY (lá
    // embaixo, perto do fim da classe) de propósito: campos static são
    // inicializados em ordem textual, então se ficasse aqui em cima
    // buildHtmlTemplate() leria os dois campos ainda como null (valor padrão,
    // antes de serem atribuídos) e "HTML_TEMPLATE" virava o texto literal
    // "nullnull" — foi exatamente esse bug que apareceu no plugin.
    //
    // Sem "final" nos dois campos abaixo de propósito: um campo final
    // inicializado com um literal é uma "constant variable" (JLS 4.12.4) e o
    // javac tenta dobrá-la de volta numa única constante em QUALQUER lugar
    // que for referenciada — inclusive dentro de buildHtmlTemplate() —
    // estourando o limite de 64KB de novo.
    private static String HTML_TEMPLATE_HEAD = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <link rel="preconnect" href="https://fonts.googleapis.com">
        <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
        <link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500;600&display=swap" rel="stylesheet">
        <script src="https://d3js.org/d3.v7.min.js"></script>
        <style>
          /* Sempre tema claro — mais legível pra visualização de dados, e os
             snapshots do pipeline headless já eram sempre claros. Valores
             fixos em vez de injetados via JS a partir do tema do IntelliJ
             (não existe mais um __THEME__/const THEME nem um objeto por
             tema); os nomes das variáveis continuam os mesmos de antes, só
             que agora como :root estático, então nenhuma outra regra do CSS
             abaixo precisou mudar. */
          :root {
            --bg:                   #FAFAFA;
            --package-fill:         #F0F2F4;
            --package-stroke:       #AAAAAA;
            --text:                 #1A1A1A;
            --text-secondary:       #666666;
            --panel:                #F7F7F7;
            --panel-border:         #DDDDDD;
            --bar-bg:               #E0E0E0;
            --bar-fan-in:           #1565C0;
            --bar-fan-out:          #E65100;
            --button-text:          #FFFFFF;
            --divider:              #DDDDDD;
            --cycle-line:           #E53935;
            --title-bg:             #f7f8fa;
            --title-border:         #d8dae0;
            --title-text:           #27282e;
            --title-text-secondary: #8a8e99;
            --input-bg:             #ffffff;
            --input-border:         #c9ccd4;
            --input-text:           #8a8e99;
            --accent:               #3574f0;
            --seg-active-bg:        #e8ecf3;
            --seg-active-text:      #27282e;
            --seg-inactive-text:    #6c707e;
            --tooltip-bg:           #ffffff;
            --tooltip-border:       #d8dae0;
            --tooltip-shadow:       0 2px 12px rgba(0,0,0,0.12);
            --fanout-color:         #2c5fbf;
            --fanin-color:          #c2570f;
            --legend-bg2:           rgba(255,255,255,0.94);
            --legend-border2:       #e0e2e7;
            --sidebar-bg:           #ffffff;
            --sidebar-bg-subtle:    #f7f8fa;
            --sidebar-bg-hover:     #f2f3f5;
            --sidebar-border:       #d8dae0;
            --sidebar-border-subtle:#eceef1;
            --sidebar-text:         #27282e;
            --sidebar-text-secondary: #494b57;
            --sidebar-text-tertiary: #8a8e99;
            --cycle-cell-bg:        #fdf1f0;
            --cycle-cell-number:    #c0392f;
            --cycle-alert-bg:       #fdf1f0;
            --cycle-alert-border:   #f5cfca;
            --cycle-alert-text:     #9c2f25;
            --cycles-mode-text:     #c0392f;
            --compare-orange:       #e0701f;
            --badge-shadow:         0 2px 8px rgba(20,30,60,0.06);
          }
          html, body {
            margin: 0; height: 100%; overflow: hidden;
            font-family: 'IBM Plex Sans', system-ui, sans-serif;
            background: var(--bg); color: var(--text);
          }
          body { display: flex; flex-direction: column; }

          /* Nível 1 — título */
          #titlebar {
            flex: 0 0 34px; display: flex; align-items: center; padding: 0 12px;
            background: var(--title-bg); border-bottom: 1px solid var(--title-border);
          }
          #titlebar-name { font: 600 12.5px 'IBM Plex Sans'; color: var(--title-text); }
          #titlebar-project-sep, #titlebar-project-name {
            font: 400 11px 'IBM Plex Sans'; color: var(--title-text-secondary);
          }
          .titlebar-spacer { flex: 1; }
          /* Decorativos — o fechar/menu reais da ferramenta ficam no chrome do IntelliJ. */
          .titlebar-btn { font-size: 13px; color: var(--title-text-secondary); margin-left: 14px; opacity: 0.8; }

          /* Nível 2 — controles */
          #controlsbar {
            flex: 0 0 42px; display: flex; align-items: center; gap: 10px; padding: 0 12px;
            background: var(--panel); border-bottom: 1px solid var(--panel-border);
          }
          .controls-spacer { flex: 1; }

          #btn-analyze {
            height: 26px; padding: 0 12px; border: none; border-radius: 4px;
            background: var(--accent); color: #fff; font: 500 12px 'IBM Plex Sans';
            cursor: pointer; display: inline-flex; align-items: center; gap: 6px;
          }
          #btn-analyze:disabled { opacity: 0.7; cursor: default; }
          #btn-analyze .icon { display: inline-block; }
          #btn-analyze.analyzing .icon { animation: ce-spin 0.9s linear infinite; }
          @keyframes ce-spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

          .select-wrap { position: relative; display: inline-flex; align-items: center; }
          #module-select {
            height: 26px; padding: 0 24px 0 10px; border-radius: 4px;
            background: var(--input-bg); border: 1px solid var(--input-border);
            color: var(--text); font: 400 12px 'IBM Plex Sans'; cursor: pointer;
            appearance: none; -webkit-appearance: none;
          }
          .select-arrow {
            position: absolute; right: 8px; pointer-events: none;
            font-size: 10px; color: var(--text-secondary);
          }

          #cache-toggle {
            display: flex; align-items: center; gap: 6px; cursor: pointer; user-select: none;
            font: 400 11.5px 'IBM Plex Sans'; color: var(--text);
          }
          #cache-toggle input { display: none; }
          .checkbox-box {
            width: 13px; height: 13px; min-width: 13px; border-radius: 3px;
            border: 1px solid var(--input-border); background: var(--input-bg); position: relative;
          }
          #cache-checkbox:checked + .checkbox-box { background: var(--accent); border-color: var(--accent); }
          #cache-checkbox:checked + .checkbox-box::after {
            content: ''; position: absolute; left: 3px; top: 0;
            width: 4px; height: 8px; border: solid #fff; border-width: 0 2px 2px 0;
            transform: rotate(45deg);
          }

          #status-label { font: 400 10.5px 'IBM Plex Sans'; color: var(--title-text-secondary); }
          #status-label.status-error { color: var(--cycle-line); }

          .search-wrap {
            display: flex; align-items: center; gap: 8px; width: 220px; height: 26px; padding: 0 10px;
            border-radius: 4px; background: var(--input-bg); border: 1px solid var(--input-border);
          }
          .search-icon { color: var(--input-text); font-size: 13px; }
          #search-input {
            flex: 1; min-width: 0; border: none; outline: none; background: transparent;
            color: var(--text); font: 400 12px 'IBM Plex Sans';
          }
          #search-input::placeholder { color: var(--input-text); }

          /* Badge "Modo ciclos" — substitui o dropdown de módulo na barra
             superior enquanto o modo ciclos (Parte 4) está ativo. */
          #cycles-mode-badge {
            display: none;
            align-items: center; gap: 6px; height: 26px; padding: 0 6px 0 10px; border-radius: 4px;
            background: var(--cycle-alert-bg); border: 1px solid var(--cycle-alert-border); color: var(--cycles-mode-text);
            font: 500 11.5px 'IBM Plex Sans';
          }
          .cycles-mode-exit-btn { padding: 1px 6px; border-radius: 3px; background: rgba(0,0,0,0.15); font-weight: 400; cursor: pointer; }

          #app { flex: 1 1 auto; display: flex; min-height: 0; }
          #chart-container { flex: 1 1 auto; position: relative; background: var(--bg); min-width: 0; }
          svg { width: 100%; height: 100%; display: block; }
          #resizer { flex: 0 0 6px; cursor: col-resize; background: var(--divider); }
          #resizer:hover, #resizer.dragging { background: var(--bar-fan-in); }
          #sidebar {
            flex: 0 0 260px; background: var(--sidebar-bg);
            border-left: 1px solid var(--sidebar-border);
            box-shadow: -2px 0 8px rgba(0,0,0,0.08);
            overflow-y: auto; padding: 0; box-sizing: border-box; font-size: 13px; color: var(--sidebar-text);
            word-break: break-word;
          }

          /* Inspetor — estado padrão */
          .insp-summary { display: grid; grid-template-columns: 1fr 1fr 1fr 1fr; border-bottom: 1px solid var(--sidebar-border); }
          .insp-summary-cell {
            padding: 14px 0 13px 16px; display: flex; flex-direction: column; gap: 3px;
            border-left: 1px solid var(--sidebar-border-subtle);
          }
          .insp-summary-cell:first-child { border-left: none; padding-left: 12px; }
          .insp-summary-cell.cycles { background: var(--cycle-cell-bg); cursor: pointer; }
          .insp-summary-number { font: 600 20px/1 'IBM Plex Mono'; color: var(--sidebar-text); }
          .insp-summary-cell.cycles .insp-summary-number { color: var(--cycle-cell-number); }
          .insp-summary-label { font: 400 10px 'IBM Plex Sans'; color: var(--sidebar-text-tertiary); }
          .insp-summary-cell.cycles .insp-summary-label { color: var(--cycle-cell-number); }

          .insp-tabs {
            display: flex; gap: 18px; padding: 0 16px; border-bottom: 1px solid var(--sidebar-border);
            font: 400 12px 'IBM Plex Sans';
          }
          .insp-tab {
            padding: 9px 0; color: var(--sidebar-text-tertiary); cursor: pointer;
            border-bottom: 2px solid transparent; display: flex; align-items: center; gap: 5px;
          }
          .insp-tab.active { color: var(--sidebar-text); font-weight: 500; border-bottom-color: #3574f0; }
          .insp-tab-count { font: 500 10px 'IBM Plex Mono'; color: #f0857c; }

          .insp-table-header {
            display: flex; align-items: center; gap: 10px; padding: 9px 16px 7px;
            font: 500 9.5px 'IBM Plex Sans'; letter-spacing: .09em; text-transform: uppercase;
            color: var(--sidebar-text-tertiary);
          }
          .insp-col-check { width: 13px; flex: 0 0 13px; display: flex; align-items: center; }
          .insp-col-name { flex: 1; min-width: 0; }
          .insp-col-classes { width: 52px; flex: 0 0 52px; text-align: right; }
          .insp-col-fanout { width: 68px; flex: 0 0 68px; text-align: right; }
          .insp-col-classpkg { width: 90px; flex: 0 0 90px; }
          .insp-sortable { cursor: pointer; user-select: none; display: inline-flex; align-items: center; gap: 3px; }
          .insp-col-classes.insp-sortable, .insp-col-fanout.insp-sortable { justify-content: flex-end; }
          .insp-sort-arrow { color: var(--sidebar-text-tertiary); font-size: 9px; }
          .insp-sort-arrow.active { color: var(--sidebar-text); }

          .insp-row { height: 30px; }
          .insp-class-row { height: 28px; }
          .insp-row, .insp-class-row {
            display: flex; align-items: center; gap: 10px; padding: 0 16px;
            border-top: 1px solid var(--sidebar-border-subtle); cursor: pointer;
          }
          .insp-row:hover, .insp-class-row:hover { background: var(--sidebar-bg-hover); }
          .insp-checkbox {
            width: 13px; height: 13px; min-width: 13px; border-radius: 3px;
            border: 1.5px solid var(--sidebar-text-tertiary); background: transparent; position: relative; cursor: pointer;
          }
          .insp-checkbox.checked { background: #3574f0; border-color: #3574f0; }
          .insp-checkbox.checked::after {
            content: ''; position: absolute; left: 3px; top: 0; width: 4px; height: 8px;
            border: solid #fff; border-width: 0 2px 2px 0; transform: rotate(45deg);
          }
          .insp-checkbox.indeterminate { background: var(--sidebar-text-tertiary); border-color: var(--sidebar-text-tertiary); }
          .insp-checkbox.indeterminate::after {
            content: '—'; position: absolute; left: 2px; top: -4px; color: #fff; font-size: 10px; line-height: 13px;
          }
          .insp-pkg-name {
            flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
            font: 400 11.5px 'IBM Plex Mono'; color: var(--sidebar-text-secondary);
          }
          .insp-pkg-classes { width: 52px; flex: 0 0 52px; text-align: right; font: 400 11px 'IBM Plex Mono'; color: var(--sidebar-text-tertiary); }
          .insp-pkg-fanout {
            width: 68px; flex: 0 0 68px; display: flex; align-items: center; justify-content: flex-end; gap: 7px;
            font: 500 11px 'IBM Plex Mono'; color: var(--sidebar-text);
          }
          .insp-fanout-dot { width: 10px; height: 10px; min-width: 10px; border-radius: 50%; }
          .insp-class-pkg {
            width: 90px; flex: 0 0 90px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
            font: 400 10px 'IBM Plex Mono'; color: var(--sidebar-text-tertiary);
          }

          .insp-scroll-area { overflow-y: auto; max-height: calc(100vh - 280px); }
          .insp-scroll-area::-webkit-scrollbar { width: 4px; }
          .insp-scroll-area::-webkit-scrollbar-track { background: transparent; }
          .insp-scroll-area::-webkit-scrollbar-thumb { background: var(--sidebar-border); border-radius: 2px; }

          .insp-cycle-row {
            display: flex; align-items: center; gap: 10px; height: 36px; padding: 0 16px;
            border-top: 1px solid var(--sidebar-border-subtle); cursor: pointer;
          }
          .insp-cycle-row:hover, .insp-cycle-row.active { background: var(--sidebar-bg-hover); }
          .insp-cycle-chevron {
            flex: none; width: 12px; font-size: 9px; color: var(--sidebar-text-tertiary);
            cursor: pointer; transition: transform 150ms;
          }
          .insp-cycle-chevron.expanded { transform: rotate(90deg); }
          .insp-cycle-number {
            width: 20px; height: 20px; border-radius: 50%; color: #fff;
            font: 600 11px 'IBM Plex Mono'; display: grid; place-items: center; flex: none;
          }
          .insp-cycle-path {
            flex: 1; min-width: 0; font: 400 11px 'IBM Plex Mono'; color: var(--sidebar-text-secondary);
            white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
          }
          .insp-cycle-detail {
            padding: 6px 16px 10px 38px; font: 400 10.5px 'IBM Plex Mono'; color: var(--sidebar-text-secondary);
            line-height: 1.6; word-break: break-word;
          }
          .insp-cycle-hop-type {
            font-size: 9px; color: var(--sidebar-text-tertiary); text-transform: uppercase; margin: 0 3px;
          }

          .insp-footnote { padding: 8px 16px; font: 400 10px 'IBM Plex Sans'; color: var(--sidebar-text-tertiary); border-top: 1px solid var(--sidebar-border); }
          .insp-empty { padding: 16px; font: 400 12px 'IBM Plex Sans'; color: var(--sidebar-text-tertiary); }

          /* Inspetor — classe selecionada */
          .insp-class-header { display: flex; align-items: center; gap: 10px; padding: 14px 16px 13px; border-bottom: 1px solid var(--sidebar-border); }
          .insp-class-dot { width: 28px; height: 28px; min-width: 28px; border-radius: 50%; }
          .insp-class-dot.ringed { box-shadow: 0 0 0 2px var(--sidebar-bg), 0 0 0 4px #f2762e; }
          .insp-class-header-text { flex: 1; min-width: 0; }
          .insp-class-title { font: 600 14px 'IBM Plex Mono'; color: var(--sidebar-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
          .insp-class-qname { font: 400 10.5px 'IBM Plex Mono'; color: var(--sidebar-text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
          .insp-class-badge {
            padding: 2px 6px; border-radius: 3px; background: var(--sidebar-bg-subtle);
            font: 500 9px 'IBM Plex Sans'; letter-spacing: .08em; color: var(--sidebar-text-secondary); white-space: nowrap;
          }
          .insp-close-btn { border: none; background: none; cursor: pointer; color: var(--sidebar-text-tertiary); font-size: 14px; padding: 2px 4px; }

          .insp-cycle-alert {
            display: flex; align-items: center; gap: 8px; margin: 12px 16px 0; padding: 8px 10px; border-radius: 5px;
            background: var(--cycle-alert-bg); border: 1px solid var(--cycle-alert-border); color: var(--cycle-alert-text);
            font: 400 11.5px 'IBM Plex Sans';
          }
          .insp-cycle-alert a { margin-left: auto; color: inherit; font-weight: 600; cursor: pointer; text-decoration: underline; white-space: nowrap; }
          .insp-cycle-alert.emphasized { border-width: 2px; box-shadow: 0 0 0 1px var(--cycle-alert-border); }

          .insp-metric-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; margin-top: 12px; border-top: 1px solid var(--sidebar-border); border-bottom: 1px solid var(--sidebar-border); }
          .insp-metric-cell { padding: 10px 0 9px 16px; display: flex; flex-direction: column; gap: 3px; border-left: 1px solid var(--sidebar-border-subtle); }
          .insp-metric-cell:first-child { border-left: none; }
          .insp-metric-number { font: 600 18px/1 'IBM Plex Mono'; color: var(--sidebar-text); }
          .insp-metric-number.fanout { color: var(--fanout-color); }
          .insp-metric-number.fanin { color: var(--fanin-color); }
          .insp-metric-label { font: 400 10px 'IBM Plex Sans'; color: var(--sidebar-text-tertiary); }

          .insp-compare { padding: 10px 16px 12px; }
          .insp-compare-title { font: 500 9.5px 'IBM Plex Sans'; letter-spacing: .1em; text-transform: uppercase; color: var(--sidebar-text-tertiary); margin-bottom: 8px; }
          .compare-row { display: flex; align-items: center; gap: 10px; margin: 6px 0; }
          .compare-label { width: 34px; flex: 0 0 34px; font: 400 11px 'IBM Plex Mono'; color: var(--sidebar-text-secondary); }
          .compare-bar-bg { flex: 1; height: 4px; border-radius: 3px; background: var(--sidebar-bg-subtle); position: relative; }
          .compare-bar-fill { position: absolute; left: 0; top: 0; height: 100%; border-radius: 3px; }
          .compare-median { position: absolute; width: 1px; height: 10px; background: var(--sidebar-text-tertiary); top: -3px; }
          .compare-value { width: 26px; flex: 0 0 26px; text-align: right; font: 600 11px 'IBM Plex Mono'; color: var(--sidebar-text); }
          .insp-compare-note { font: 400 9.5px 'IBM Plex Sans'; color: var(--sidebar-text-tertiary); margin-top: 4px; }

          .insp-rel-tabs { display: flex; gap: 18px; padding: 0 16px; border-top: 1px solid var(--sidebar-border); border-bottom: 1px solid var(--sidebar-border); font: 400 12px 'IBM Plex Sans'; }
          .insp-rel-tab { display: flex; align-items: center; gap: 7px; padding: 9px 0; color: var(--sidebar-text-tertiary); cursor: pointer; border-bottom: 2px solid transparent; }
          .insp-rel-tab.active { color: var(--sidebar-text); font-weight: 500; border-bottom-color: #3574f0; }
          .insp-rel-dot { width: 8px; height: 8px; border-radius: 50%; box-sizing: border-box; border: 2px solid #3574f0; }
          .insp-rel-dot.out { border-color: #e0701f; }

          .insp-rel-item { display: flex; align-items: center; gap: 8px; height: 28px; padding: 0 16px; }
          .insp-rel-name { font: 400 11.5px 'IBM Plex Mono'; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--sidebar-text); cursor: pointer; }
          .insp-rel-badge { padding: 1px 5px; border-radius: 3px; background: #fdeceb; font: 500 9px 'IBM Plex Sans'; color: #c0392f; white-space: nowrap; }
          .insp-rel-type { font: 400 10px 'IBM Plex Mono'; color: var(--sidebar-text-tertiary); white-space: nowrap; }
          .insp-rel-more { color: var(--sidebar-text-secondary); font: 400 11.5px 'IBM Plex Mono'; padding: 4px 16px; cursor: pointer; }
          .insp-rel-more:hover { color: var(--sidebar-text); }

          .insp-actions { display: flex; gap: 8px; padding: 10px 16px; border-top: 1px solid var(--sidebar-border); background: var(--sidebar-bg-subtle); }
          .insp-btn-primary { flex: 1; height: 28px; border: none; border-radius: 4px; background: #3574f0; color: #fff; font: 500 12px 'IBM Plex Sans'; cursor: pointer; }
          .insp-btn-secondary {
            height: 28px; padding: 0 12px; border-radius: 4px; border: 1px solid var(--sidebar-border); background: transparent;
            color: var(--sidebar-text); font: 500 12px 'IBM Plex Sans'; cursor: pointer;
          }
          .insp-btn-secondary.active { background: var(--sidebar-bg-hover); }

          /* Legenda — faixa fixa no rodapé do mapa */
          #legend {
            display: none;
            position: absolute; left: 14px; bottom: 12px;
            align-items: center; gap: 14px; padding: 7px 12px;
            border-radius: 6px; background: var(--legend-bg2); border: 1px solid var(--legend-border2);
            font-family: 'IBM Plex Mono', monospace;
            z-index: 10; box-shadow: 0 2px 6px rgba(0,0,0,0.15);
            color: var(--text); pointer-events: none;
          }
          .legend-item { display: flex; align-items: center; gap: 6px; height: 18px; }
          .legend-divider { width: 1px; height: 14px; background: var(--panel-border); }
          .viridis-bar {
            width: 60px; height: 6px; min-width: 60px; border-radius: 3px;
            background: linear-gradient(90deg, #4b2f86, #3574f0, #2aa3a3, #8fd14f, #e6d43a);
          }
          .legend-key { font-size: 10px; font-weight: 600; color: var(--text); white-space: nowrap; }
          .legend-desc { font-size: 10px; color: var(--text-secondary); white-space: nowrap; }
          .legend-cycle-dot { width: 9px; height: 9px; min-width: 9px; border-radius: 50%; background: #f0564a; }

          #hint {
            display: none;
            position: absolute; bottom: 10px; left: 12px;
            font-family: 'IBM Plex Sans', sans-serif; font-size: 10px; color: var(--text-secondary);
            opacity: 0.65; pointer-events: none; z-index: 10;
          }

          #empty-state {
            display: none; position: absolute; inset: 0; align-items: center; justify-content: center;
            color: var(--text-secondary); font: 400 13px 'IBM Plex Sans'; text-align: center; padding: 20px;
          }

          #tooltip {
            display: none; position: absolute; width: 200px; padding: 10px 12px 11px;
            border-radius: 6px; pointer-events: none; z-index: 20;
            background: var(--tooltip-bg); border: 1px solid var(--tooltip-border); box-shadow: var(--tooltip-shadow);
          }
          .tooltip-name { font: 600 13px 'IBM Plex Sans'; color: var(--text); margin-bottom: 2px; }
          .tooltip-qname { font: 400 10px 'IBM Plex Mono'; color: var(--text-secondary); margin-bottom: 8px; word-break: break-all; }
          .tooltip-metrics { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 4px; text-align: center; }
          .tooltip-value { font: 600 13px 'IBM Plex Mono'; color: var(--text); }
          .tooltip-value.fanout { color: var(--fanout-color); }
          .tooltip-value.fanin { color: var(--fanin-color); }
          .tooltip-label { font-size: 9px; color: var(--text-secondary); text-transform: uppercase; letter-spacing: 0.4px; margin-top: 2px; }

          .node-label { font: 10px sans-serif; pointer-events: none; text-anchor: middle; fill: var(--text); }

          /* Breadcrumb — canto superior esquerdo do mapa */
          #breadcrumb {
            display: none;
            position: absolute; left: 14px; top: 12px;
            font: 400 11px 'IBM Plex Mono'; color: var(--text-secondary);
            z-index: 10; pointer-events: none;
          }
          #breadcrumb .breadcrumb-sep { color: var(--text-secondary); opacity: 0.6; }
          #breadcrumb .breadcrumb-current { color: var(--text); }

          /* Badge flutuante de contadores — canto superior direito do mapa */
          #selection-badge {
            display: none;
            position: absolute; right: 14px; top: 10px;
            align-items: center; gap: 12px; padding: 6px 8px 6px 12px;
            border-radius: 6px;
            background: var(--tooltip-bg); border: 1px solid var(--tooltip-border);
            box-shadow: var(--badge-shadow);
            font: 400 11px 'IBM Plex Sans'; color: var(--text);
            z-index: 15;
          }
          .badge-item { display: flex; align-items: center; gap: 5px; }
          .badge-dot { width: 9px; height: 9px; min-width: 9px; border-radius: 50%; border: 2px solid; box-sizing: border-box; }
          .badge-dot.uses { border-color: #3574f0; }
          .badge-dot.usedby { border-color: #e0701f; }
          .badge-clear { padding: 3px 8px; border-radius: 4px; background: var(--sidebar-bg-subtle); cursor: pointer; }
          .badge-esc { opacity: 0.6; margin-left: 2px; }
        </style>
        </head>
        <body>
        <div id="titlebar">
          <span id="titlebar-name">CodeExplorer</span>
          <span id="titlebar-project-sep">&nbsp;·&nbsp;</span>
          <span id="titlebar-project-name"></span>
          <div class="titlebar-spacer"></div>
          <span class="titlebar-btn">⋮</span>
          <span class="titlebar-btn">—</span>
        </div>
        <div id="controlsbar">
          <button id="btn-analyze"><span class="icon">↻</span> Analisar</button>
          <div class="select-wrap">
            <select id="module-select"></select>
            <span class="select-arrow">▾</span>
          </div>
          <div id="cycles-mode-badge">
            ⚠ Modo ciclos
            <span class="cycles-mode-exit-btn" data-exit-cycles-mode>Sair <span class="badge-esc">Esc</span></span>
          </div>
          <label id="cache-toggle">
            <input type="checkbox" id="cache-checkbox">
            <span class="checkbox-box"></span>
            Usar cache
          </label>
          <span id="status-label"></span>
          <div class="controls-spacer"></div>
          <div class="search-wrap">
            <span class="search-icon">⌕</span>
            <input id="search-input" type="text" placeholder="Buscar classe ou pacote">
          </div>
        </div>
        <div id="app">
          <div id="chart-container">
            <div id="hint">Clique num pacote para zoom · clique numa classe para detalhes · clique fora para voltar</div>
            <div id="empty-state">Nenhuma análise ainda — clique em <b>&nbsp;Analisar&nbsp;</b>.</div>
            <div id="legend">
              <div class="legend-item">
                <div class="viridis-bar"></div>
                <span class="legend-key">0</span>
                <span class="legend-key" id="legend-max">0</span>
                <span class="legend-desc" id="legend-color-label">fan-out</span>
              </div>
              <div class="legend-divider"></div>
              <div class="legend-item">
                <svg width="26" height="14" viewBox="0 0 26 14">
                  <circle cx="5"  cy="7" r="3" fill="#35B779"/>
                  <circle cx="19" cy="7" r="5.5" fill="#35B779"/>
                </svg>
                <span class="legend-desc">tamanho = LOC</span>
              </div>
              <div class="legend-divider"></div>
              <div class="legend-item">
                <svg width="18" height="18" viewBox="0 0 18 18">
                  <circle cx="9" cy="9" r="4.5" fill="#35B779"/>
                  <circle cx="9" cy="9" r="7" fill="none"
                          stroke="#FF6B00" stroke-width="2"
                          stroke-dasharray="28 44" stroke-linecap="round"
                          transform="rotate(-90 9 9)"/>
                </svg>
                <span class="legend-desc">anel = fan-in</span>
              </div>
              <div class="legend-divider cycle-legend-extra" style="display:none"></div>
              <div class="legend-item cycle-legend-extra" style="display:none">
                <span class="legend-cycle-dot"></span>
                <span class="legend-desc">em ciclo</span>
              </div>
            </div>
            <div id="tooltip"></div>
            <div id="breadcrumb"></div>
            <div id="selection-badge">
              <span class="badge-item"><span class="badge-dot uses"></span>usa · <span id="badge-uses-count">0</span></span>
              <span class="badge-item"><span class="badge-dot usedby"></span>usada por · <span id="badge-usedby-count">0</span></span>
              <span class="badge-clear" data-clear-selection>Limpar <span class="badge-esc">Esc</span></span>
            </div>
            <svg id="chart"></svg>
          </div>
          <div id="resizer"></div>
          <div id="sidebar"></div>
        </div>
        <script>
          const INIT_DATA = __INIT_DATA_JSON__;
          const GRAPH_DATA = __GRAPH_DATA_JSON__;
        </script>
        """;

    private static String HTML_TEMPLATE_BODY = """
        <script>
        (function () {
          wireTopBar();
          if (GRAPH_DATA) {
            renderGraph(GRAPH_DATA);
          } else {
            document.getElementById("empty-state").style.display = "flex";
          }

          function escapeHtml(s) {
            return String(s).replace(/[&<>"']/g, c => ({
              '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
            }[c]));
          }

          function relativeTime(ms) {
            const diffSec = Math.max(0, Math.floor((Date.now() - ms) / 1000));
            if (diffSec < 60) return "agora mesmo";
            const diffMin = Math.floor(diffSec / 60);
            if (diffMin < 60) return `há ${diffMin} min`;
            const diffH = Math.floor(diffMin / 60);
            if (diffH < 24) return `há ${diffH} h`;
            const diffD = Math.floor(diffH / 24);
            return `há ${diffD} d`;
          }

          // Nível 1 (título) + nível 2 (controles): botão Analisar, dropdown
          // de módulo, checkbox de cache e timestamp — tudo dentro do HTML
          // agora, ligado ao Java real via o bridge JCEF (analyzeQuery).
          function wireTopBar() {
            document.getElementById("titlebar-project-name").textContent = INIT_DATA.projectName || "";

            const moduleSelect = document.getElementById("module-select");
            const selectWrap = moduleSelect.closest(".select-wrap");
            if (INIT_DATA.modules && INIT_DATA.modules.length > 0) {
              moduleSelect.innerHTML = INIT_DATA.modules.map(m =>
                `<option value="${escapeHtml(m)}"${m === INIT_DATA.selectedModule ? " selected" : ""}>${escapeHtml(m)}</option>`
              ).join("");
            } else {
              selectWrap.style.display = "none";
            }

            const cacheCheckbox = document.getElementById("cache-checkbox");
            cacheCheckbox.checked = !!INIT_DATA.useCache;

            const statusLabel = document.getElementById("status-label");
            function updateStatus() {
              if (INIT_DATA.statusMessage) {
                statusLabel.textContent = INIT_DATA.statusMessage;
                statusLabel.classList.add("status-error");
                return;
              }
              statusLabel.classList.remove("status-error");
              statusLabel.textContent = INIT_DATA.lastAnalyzedAt
                ? "analisado " + relativeTime(INIT_DATA.lastAnalyzedAt)
                : "ainda não analisado";
            }
            updateStatus();
            setInterval(updateStatus, 30000);

            const btn = document.getElementById("btn-analyze");
            btn.addEventListener("click", () => {
              btn.disabled = true;
              btn.classList.add("analyzing");
              const moduleName = selectWrap.style.display !== "none" ? (moduleSelect.value || "") : "";
              const useCache = cacheCheckbox.checked;
              const payload = moduleName + "|" + (useCache ? "1" : "0");
              __ANALYZE_QUERY_JS__
            });
          }

          function renderGraph(data) {
            document.getElementById("hint").style.display = "block";
            document.getElementById("legend").style.display = "flex";

            const width = 928, height = 928;
            const sidebar = document.getElementById("sidebar");
            const chartContainer = document.getElementById("chart-container");
            const tooltip = document.getElementById("tooltip");

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
            // classes, que são a informação real. A separação entre pacotes
            // aninhados vem da borda (stroke), não do preenchimento.
            const PACKAGE_FILL = "#F0F2F4";
            const PACKAGE_STROKE = "#AAAAAA";

            // Contorno padrão de toda folha (classe) — fino e semitransparente,
            // fixo independente da cor de fill (ver defaultLeafStroke abaixo).
            const DEFAULT_LEAF_STROKE = "rgba(0, 0, 0, 0.18)";
            const DEFAULT_LEAF_STROKE_WIDTH = 0.8;

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

            const leaves = root.descendants().filter(d => !d.children);

            // Fill das classes = fan-out normalizado pelo máximo do projeto,
            // via escala contínua Viridis — sem limiares arbitrários de
            // severidade. Fan-out baixo cai no roxo escuro, alto no amarelo.
            const maxFanOut = GRAPH_DATA.maxFanOut || 1;

            function leafColor(d) {
              const ratio = maxFanOut > 0 ? d.data.fanOut / maxFanOut : 0;
              return d3.interpolateViridis(ratio);
            }

            document.getElementById("legend-color-label").textContent = "fan-out";
            document.getElementById("legend-max").textContent = Math.round(maxFanOut);

            let focus = root;
            let view;
            let k = 1;
            let selectedQName = null;

            // Estado do inspetor lateral (Parte 2): aba ativa, pacotes ocultos
            // via checkbox, isolamento de uma classe + vizinhos diretos e
            // busca (compartilhada com o campo do topo).
            let activeTab = "packages";
            let selectedRelTab = "uses";
            let searchQuery = "";
            let isolateQName = null;
            const cyclesData = data.cycles || [];
            const hiddenPackages = new Set();

            // Expansão de "+ N outras" nas listas Usa/Usada por — reseta
            // quando a classe selecionada muda, mas persiste ao trocar entre
            // as abas Usa/Usada por ou ao re-renderizar pra mesma classe.
            let relListExpandedQName = null;
            let relListExpanded = { uses: false, usedBy: false };

            // Ordenação das abas Pacotes/Classes (Parte 5) — estado
            // independente por aba, já que as colunas diferem.
            let pkgSortCol = "classes";
            let pkgSortDir = "desc";
            let classSortCol = "fanout";
            let classSortDir = "desc";

            // Modo ciclos (Parte 4): substitui o destaque de seleção da Parte 3
            // no mapa enquanto ativo — ver applySelectionVisuals() e
            // applyCyclesModeVisuals(). cyclesModeFocusIndex null = mostra
            // todos os ciclos; um número narrows pro ciclo clicado na lista.
            let cyclesMode = false;
            let cyclesModeFocusIndex = null;
            let cyclesModeExpandedIndex = null;

            const CYCLE_COLORS = ["#f0564a", "#e0701f", "#d4a017", "#9b59b6", "#e91e8c"];
            function cycleColor(index) { return CYCLE_COLORS[index % CYCLE_COLORS.length]; }

            // Índices (0-based) de todos os ciclos que contêm esta classe —
            // uma classe pode participar de mais de um ciclo ao mesmo tempo.
            function cyclesForNode(qName) {
              const result = [];
              for (let i = 0; i < cyclesData.length; i++) {
                if (cyclesData[i].includes(qName)) result.push(i);
              }
              return result;
            }

            const svg = d3.select("#chart")
                .attr("viewBox", `-${width / 2} -${height / 2} ${width} ${height}`)
                .style("cursor", "pointer")
                .on("click", (event) => {
                  selectedQName = null;
                  isolateQName = null;
                  applySelectionVisuals();
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
                .attr("class", d => d.children ? "node-package" : "node-leaf")
                .attr("fill", d => d.children ? PACKAGE_FILL : leafColor(d))
                .attr("fill-opacity", d => d.children ? 0.85 : 0.9)
                // Borda das folhas: preto semitransparente fixo, não a cor do
                // fill escurecida — em projetos grandes, círculos roxo/azul
                // escuro da Viridis (fan-out baixo) ficavam sem contraste
                // contra o fundo, sobretudo os bem pequenos. Um contorno fixo
                // garante toda classe visível como forma distinta, qualquer
                // que seja sua cor. Não é a cor de tema — fica igual sempre.
                .attr("stroke", d => d.children ? PACKAGE_STROKE : DEFAULT_LEAF_STROKE)
                .attr("stroke-width", d => d.children ? 1 : DEFAULT_LEAF_STROKE_WIDTH)
                .attr("stroke-opacity", d => d.children ? 0.6 : 1)
                .on("mouseover", function (event, d) {
                  d3.select(this).attr("stroke-opacity", 1);
                  showTooltip(event, d);
                })
                .on("mousemove", (event, d) => showTooltip(event, d))
                .on("mouseout", function (event, d) {
                  d3.select(this).attr("stroke-opacity", d.children ? 0.4 : 1);
                  hideTooltip();
                })
                .on("click", (event, d) => {
                  event.stopPropagation();
                  if (!d.children) {
                    const wasSelected = selectedQName === d.data.qualifiedName;
                    selectedQName = wasSelected ? null : d.data.qualifiedName;
                    if (isolateQName && isolateQName !== selectedQName) {
                      isolateQName = null;
                    }
                    applySelectionVisuals();
                    renderSidebar();
                    hideTooltip();
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

              updateBreadcrumb();
            }

            // Classifica uma folha em relação à classe selecionada, pra decidir
            // cor de contorno e opacidade — em vez de desenhar linhas de
            // dependência (hairball problem), as classes relacionadas "acendem"
            // com cores diferentes e o resto apaga.
            function classifyNode(d, selectedQN) {
              if (!selectedQN) return null;
              if (d.data.qualifiedName === selectedQN) return "self";
              const usedBySelected = (data.edges || []).some(e =>
                e.source === selectedQN && e.target === d.data.qualifiedName);
              const usesSelected = (data.edges || []).some(e =>
                e.target === selectedQN && e.source === d.data.qualifiedName);
              if (usedBySelected && usesSelected) return "both";
              if (usedBySelected) return "uses";   // selecionada usa esta
              if (usesSelected)   return "usedby"; // esta usa a selecionada
              return "unrelated";
            }

            function strokeForClassification(c) {
              if (c === "self")   return "#ffffff";
              if (c === "uses")   return "#3574f0";
              if (c === "usedby") return "#e0701f";
              if (c === "both")   return "#9b59b6";
              return "none";
            }

            function strokeWidthForClassification(c) {
              if (c === "self") return 2.5;
              if (c === "uses" || c === "usedby" || c === "both") return 3;
              return 0;
            }

            // Contorno padrão de uma folha (sem seleção/ciclo ativo) — o
            // mesmo preto semitransparente fixo do render inicial (ver
            // DEFAULT_LEAF_STROKE), não mais a cor do fill escurecida.
            function defaultLeafStroke(d) {
              return DEFAULT_LEAF_STROKE;
            }

            // Apaga o resto do mapa e acende as classes relacionadas com a
            // selecionada (azul = usa, laranja = usada por, roxo = ambos).
            // "Isolar no mapa" (inspetor) reusa esta mesma classificação, só
            // com um limiar de opacidade mais agressivo pras não-relacionadas.
            //
            // Em modo ciclos (Parte 4), o mapa é "dono" do stroke/opacidade
            // dos círculos (ver applyCyclesModeVisuals) — clicar numa classe
            // ainda abre o inspetor normalmente, mas não deve reaplicar o
            // destaque da Parte 3 por cima. Um guard aqui, em vez de espalhar
            // a checagem em cada chamador, cobre todos os pontos de entrada
            // (clique no mapa, seleção pelo inspetor, isolar, deselecionar).
            function applySelectionVisuals() {
              if (cyclesMode) return;
              if (!selectedQName) {
                d3.selectAll(".node-leaf")
                  .transition().duration(200).ease(d3.easeQuadOut)
                  .attr("opacity", 1)
                  .attr("stroke", d => defaultLeafStroke(d))
                  .attr("stroke-width", DEFAULT_LEAF_STROKE_WIDTH);
                d3.selectAll(".node-package")
                  .transition().duration(200).ease(d3.easeQuadOut)
                  .attr("opacity", 1);
                hideSelectionBadge();
                updateBreadcrumb();
                return;
              }

              const unrelatedOpacity = isolateQName === selectedQName ? 0.05 : 0.12;

              d3.selectAll(".node-leaf")
                .transition().duration(200).ease(d3.easeQuadOut)
                .attr("opacity", d => classifyNode(d, selectedQName) === "unrelated" ? unrelatedOpacity : 1.0)
                .attr("stroke", d => strokeForClassification(classifyNode(d, selectedQName)))
                .attr("stroke-width", d => strokeWidthForClassification(classifyNode(d, selectedQName)));

              d3.selectAll(".node-package")
                .transition().duration(200).ease(d3.easeQuadOut)
                .attr("opacity", 0.4);

              showSelectionBadge();
              updateBreadcrumb();
            }

            function showSelectionBadge() {
              const d = byQName.get(selectedQName);
              if (!d) return;
              document.getElementById("badge-uses-count").textContent = d.data.fanOut;
              document.getElementById("badge-usedby-count").textContent = d.data.fanIn;
              document.getElementById("selection-badge").style.display = "flex";
            }

            function hideSelectionBadge() {
              document.getElementById("selection-badge").style.display = "none";
            }

            function setCycleLegendVisible(visible) {
              document.querySelectorAll(".cycle-legend-extra").forEach(el => {
                el.style.display = visible ? (el.classList.contains("legend-item") ? "flex" : "block") : "none";
              });
            }

            // Ativa o modo ciclos — substitui o dropdown de módulo pelo badge
            // de aviso na barra superior, muda o inspetor pra lista de ciclos
            // e acende as classes envolvidas no mapa. focusIndex null mostra
            // todos os ciclos; um número narrows pra um ciclo específico
            // (clique na lista, ou "Ver ciclo" no alerta da classe).
            function enterCyclesMode(focusIndex) {
              if (cyclesData.length === 0) return;
              cyclesMode = true;
              cyclesModeFocusIndex = focusIndex != null ? focusIndex : null;
              selectedQName = null;
              isolateQName = null;
              activeTab = "cycles";

              const selectWrap = document.querySelector(".select-wrap");
              if (selectWrap) selectWrap.style.display = "none";
              document.getElementById("cycles-mode-badge").style.display = "flex";
              setCycleLegendVisible(true);

              applyCyclesModeVisuals();
              renderSidebar();
            }

            function exitCyclesMode() {
              cyclesMode = false;
              cyclesModeFocusIndex = null;
              cyclesModeExpandedIndex = null;
              selectedQName = null;
              activeTab = "packages";

              document.getElementById("cycles-mode-badge").style.display = "none";
              const selectWrap = document.querySelector(".select-wrap");
              if (selectWrap && INIT_DATA.modules && INIT_DATA.modules.length > 0) {
                selectWrap.style.display = "";
              }
              setCycleLegendVisible(false);

              node.filter(d => !d.children)
                .transition().duration(200).ease(d3.easeQuadOut)
                .attr("opacity", 1)
                .attr("stroke", d => defaultLeafStroke(d))
                .attr("stroke-width", DEFAULT_LEAF_STROKE_WIDTH);

              renderSidebar();
            }

            // Sem fade de opacidade e sem número sobreposto — com poucas
            // classes em ciclo (o caso comum), apagar o resto ou cravar um
            // número em cada círculo ficava poluído/difícil de enxergar.
            // Fica tudo com aparência normal (cor Viridis + borda padrão) até
            // o usuário clicar num ciclo específico na lista — só então as
            // classes daquele ciclo acendem com borda colorida. Nenhuma
            // outra classe muda, nem as de outros ciclos.
            function applyCyclesModeVisuals() {
              node.filter(d => !d.children)
                .transition().duration(200).ease(d3.easeQuadOut)
                .attr("opacity", 1)
                .attr("stroke", d => {
                  if (cyclesModeFocusIndex !== null && cyclesForNode(d.data.qualifiedName).includes(cyclesModeFocusIndex)) {
                    return cycleColor(cyclesModeFocusIndex);
                  }
                  return defaultLeafStroke(d);
                })
                .attr("stroke-width", d => {
                  if (cyclesModeFocusIndex !== null && cyclesForNode(d.data.qualifiedName).includes(cyclesModeFocusIndex)) {
                    return 3;
                  }
                  return DEFAULT_LEAF_STROKE_WIDTH;
                });
            }

            // Tipo de relação mais relevante entre duas classes específicas
            // (mesma prioridade de dedupRelations) — usado pro detalhe
            // expandido de um ciclo na lista.
            function cycleHopType(source, target) {
              const types = (data.edges || [])
                .filter(e => e.source === source && e.target === target)
                .map(e => e.type);
              if (types.length === 0) return null;
              return RELATION_PRIORITY.find(t => types.includes(t)) || types[0];
            }

            function cycleDetailHtml(cycle) {
              const full = [...cycle, cycle[0]];
              const parts = [];
              for (let i = 0; i < full.length - 1; i++) {
                const type = cycleHopType(full[i], full[i + 1]);
                parts.push(escapeHtml(lastSegment(full[i])));
                parts.push(`<span class="insp-cycle-hop-type">${type ? type.toLowerCase() : "?"}</span>→`);
              }
              parts.push(escapeHtml(lastSegment(full[full.length - 1])));
              return parts.join(" ");
            }

            // Caminho de pacotes (e classe, se houver) do nó até a raiz —
            // a raiz em si (nome do projeto/módulo) fica de fora.
            function breadcrumbSegments(d) {
              const segments = [];
              let cur = d;
              while (cur && cur.parent) {
                if (cur.children) {
                  // Pacote: pode ser uma cadeia colapsada num nó só (ex.:
                  // "dominio.model" quando nenhum dos dois níveis tinha
                  // classes/subpacotes próprios — ver collapseChain no Java).
                  // Mostra cada parte como um nível separado no breadcrumb,
                  // mesmo sendo um nó único no circle packing.
                  const parts = cur.data.name.split(".");
                  for (let i = parts.length - 1; i >= 0; i--) {
                    segments.unshift(parts[i]);
                  }
                } else {
                  segments.unshift(cur.data.name);
                }
                cur = cur.parent;
              }
              return segments;
            }

            // Com classe selecionada, mostra o caminho até ela. Sem seleção,
            // mostra o pacote do nível de zoom atual (ou some, no nível raiz).
            function updateBreadcrumb() {
              const el = document.getElementById("breadcrumb");
              const target = selectedQName ? byQName.get(selectedQName) : (focus !== root ? focus : null);
              if (!target) {
                el.style.display = "none";
                el.innerHTML = "";
                return;
              }
              const segments = breadcrumbSegments(target);
              el.style.display = "block";
              el.innerHTML = segments.map((seg, i) => {
                const isLast = i === segments.length - 1;
                const sep = i > 0 ? '<span class="breadcrumb-sep"> › </span>' : "";
                const cls = isLast && selectedQName ? "breadcrumb-current" : "";
                return sep + `<span class="${cls}">${escapeHtml(seg)}</span>`;
              }).join("");
            }

            function lastSegment(qualifiedName) {
              const i = qualifiedName.lastIndexOf(".");
              return i >= 0 ? qualifiedName.substring(i + 1) : qualifiedName;
            }

            function showTooltip(event, d) {
              if (d.children) return;
              if (d.data.qualifiedName === selectedQName) { hideTooltip(); return; }
              const [x, y] = d3.pointer(event, chartContainer);
              tooltip.innerHTML = `
                <div class="tooltip-name">${escapeHtml(d.data.name)}</div>
                <div class="tooltip-qname">${escapeHtml(d.data.qualifiedName || "")}</div>
                <div class="tooltip-metrics">
                  <div><div class="tooltip-value">${d.data.loc}</div><div class="tooltip-label">LOC</div></div>
                  <div><div class="tooltip-value fanout">${d.data.fanOut}</div><div class="tooltip-label">Fan-out</div></div>
                  <div><div class="tooltip-value fanin">${d.data.fanIn}</div><div class="tooltip-label">Fan-in</div></div>
                </div>
              `;
              tooltip.style.left = (x + 12) + "px";
              tooltip.style.top = (y + 12) + "px";
              tooltip.style.display = "block";
            }

            function hideTooltip() {
              tooltip.style.display = "none";
            }

            function packageOf(d) {
              const qn = d.data.qualifiedName;
              const nm = d.data.name;
              return qn.includes(".") ? qn.slice(0, qn.length - nm.length - 1) : "(pacote default)";
            }

            function simplePkgOf(d) {
              const pkg = packageOf(d);
              return pkg.includes(".") ? pkg.slice(pkg.lastIndexOf(".") + 1) : pkg;
            }

            // Lista de pacotes calculada a partir das folhas do GRAPH_DATA
            // (não do agrupamento de pacotes do circle packing, que pode ter
            // cadeias de nível único colapsadas — ver collapseChain no Java).
            // Todas as classes de um mesmo pacote real compartilham o mesmo
            // nó pai no layout, então zoomTarget é seguro de reutilizar.
            // Sem ordenação aqui — packagesTabHtml() ordena na hora de
            // renderizar, conforme pkgSortCol/pkgSortDir.
            const packagesList = (() => {
              const map = new Map();
              for (const leaf of leaves) {
                const pkg = packageOf(leaf);
                let entry = map.get(pkg);
                if (!entry) {
                  entry = { name: pkg, simplePkg: simplePkgOf(leaf), members: [], zoomTarget: leaf.parent };
                  map.set(pkg, entry);
                }
                entry.members.push(leaf);
              }
              const list = Array.from(map.values()).map(p => ({
                name: p.name,
                simplePkg: p.simplePkg,
                zoomTarget: p.zoomTarget,
                classCount: p.members.length,
                fanOutAvg: p.members.reduce((s, d) => s + d.data.fanOut, 0) / p.members.length
              }));

              // Quando dois ou mais pacotes têm o mesmo último segmento
              // (ex.: com.dealguard.criterio.mappers e com.dealguard.cessao.
              // mappers, ambos "mappers"), desambigua prefixando o segmento
              // anterior do qualified name ("criterio · mappers" / "cessao ·
              // mappers"). Pacotes cujo simplePkg é único continuam mostrando
              // só ele; o title com o qualified name completo é à parte,
              // sempre mostrado independente disso (ver packagesTabHtml).
              const simplePkgCounts = new Map();
              for (const p of list) {
                simplePkgCounts.set(p.simplePkg, (simplePkgCounts.get(p.simplePkg) || 0) + 1);
              }
              for (const p of list) {
                if (simplePkgCounts.get(p.simplePkg) <= 1) {
                  p.displayPkg = p.simplePkg;
                  continue;
                }
                const segments = p.name.split(".");
                const prevSegment = segments.length >= 2 ? segments[segments.length - 2] : null;
                p.displayPkg = prevSegment ? `${prevSegment} · ${p.simplePkg}` : p.simplePkg;
              }

              return list;
            })();

            function sortIndicator(col, activeCol, dir) {
              if (col !== activeCol) return "↕";
              return dir === "asc" ? "↑" : "↓";
            }

            function sortPackages(list, col, dir) {
              return [...list].sort((a, b) => {
                let va, vb;
                if (col === "name") { va = a.simplePkg; vb = b.simplePkg; }
                else if (col === "fanout") { va = a.fanOutAvg; vb = b.fanOutAvg; }
                else { va = a.classCount; vb = b.classCount; }
                if (typeof va === "string") {
                  return dir === "asc" ? va.localeCompare(vb) : vb.localeCompare(va);
                }
                return dir === "asc" ? va - vb : vb - va;
              });
            }

            function sortClasses(list, col, dir) {
              return [...list].sort((a, b) => {
                let va, vb;
                if (col === "name") { va = a.data.name; vb = b.data.name; }
                else if (col === "package") { va = simplePkgOf(a); vb = simplePkgOf(b); }
                else { va = a.data.fanOut; vb = b.data.fanOut; }
                if (typeof va === "string") {
                  return dir === "asc" ? va.localeCompare(vb) : vb.localeCompare(va);
                }
                return dir === "asc" ? va - vb : vb - va;
              });
            }

            // Opacidade dos círculos de classe e de pacote no mapa por busca
            // (topo) e pacotes ocultados via checkbox (Parte 5) — canal
            // independente da opacidade de seleção/isolamento/modo ciclos
            // (ver applySelectionVisuals/applyCyclesModeVisuals), que atuam no
            // atributo "opacity" do próprio círculo, não no <g> que o envolve.
            // Como SVG multiplica a opacidade de um elemento pela dos seus
            // ancestrais, um pacote oculto (0.06 aqui) fica nesse patamar (ou
            // mais apagado ainda) mesmo que o círculo esteja "aceso" por
            // seleção ou ciclo — satisfaz o pedido da Parte 5 sem precisar
            // duplicar a checagem de hiddenPackages dentro das Partes 3/4.
            function updateLeafVisibility() {
              const q = searchQuery.trim().toLowerCase();
              const leafGroups = nodeGroup.filter(d => !d.children);

              leafGroups
                .transition().duration(200).ease(d3.easeQuadOut)
                .style("opacity", d => {
                  if (hiddenPackages.has(packageOf(d))) return 0.06;
                  if (q) {
                    const name = (d.data.name || "").toLowerCase();
                    const qname = (d.data.qualifiedName || "").toLowerCase();
                    if (!name.includes(q) && !qname.includes(q)) return 0.15;
                  }
                  return 1;
                });
              leafGroups.style("pointer-events", d => hiddenPackages.has(packageOf(d)) ? "none" : null);

              const hiddenPkgTargets = new Set(
                packagesList.filter(p => hiddenPackages.has(p.name)).map(p => p.zoomTarget)
              );
              nodeGroup.filter(d => d.children)
                .transition().duration(200).ease(d3.easeQuadOut)
                .style("opacity", d => hiddenPkgTargets.has(d) ? 0.25 : null);
            }

            // Filtra os círculos de classe visíveis no mapa pelo texto digitado,
            // comparando contra nome simples e qualified name (case-insensitive).
            function wireSearch() {
              const input = document.getElementById("search-input");
              input.addEventListener("input", (e) => {
                searchQuery = e.target.value;
                updateLeafVisibility();
              });
            }

            function selectClass(qName) {
              const d = byQName.get(qName);
              if (!d) return;
              if (isolateQName && isolateQName !== qName) {
                isolateQName = null;
              }
              selectedQName = qName;
              applySelectionVisuals();
              hideTooltip();
              renderSidebar();
              if (focus !== d) zoom({ altKey: false }, d);
            }

            function deselect() {
              selectedQName = null;
              isolateQName = null;
              applySelectionVisuals();
              renderSidebar();
              zoom({ altKey: false }, root);
            }

            // Reusa a mesma classificação de applySelectionVisuals, só com um
            // limiar de opacidade mais agressivo (0.05) pras não-relacionadas —
            // ver o parâmetro unrelatedOpacity ali.
            function toggleIsolate() {
              if (!selectedQName) return;
              isolateQName = isolateQName === selectedQName ? null : selectedQName;
              applySelectionVisuals();
              renderSidebar();
            }

            function inSameCycle(a, b) {
              return cyclesData.some(cycle => cycle.includes(a) && cycle.includes(b));
            }

            // Delegação de eventos no container do inspetor: sobrevive às
            // trocas de innerHTML feitas por renderSidebar(), então só
            // precisa ser ligada uma vez.
            function wireSidebarEvents() {
              sidebar.addEventListener("click", (event) => {
                // Chevron de expandir/colapsar um ciclo na lista — checado
                // antes de data-cycle-index, já que fica aninhado na linha.
                const expandToggle = event.target.closest("[data-toggle-cycle-expand]");
                if (expandToggle) {
                  const idx = Number(expandToggle.dataset.toggleCycleExpand);
                  cyclesModeExpandedIndex = cyclesModeExpandedIndex === idx ? null : idx;
                  renderSidebar();
                  return;
                }

                const tab = event.target.closest("[data-tab]");
                if (tab) {
                  const target = tab.dataset.tab;
                  if (target === "cycles" && cyclesData.length > 0) {
                    enterCyclesMode(cyclesMode ? cyclesModeFocusIndex : null);
                  } else {
                    if (cyclesMode) exitCyclesMode();
                    activeTab = target;
                    renderSidebar();
                  }
                  return;
                }

                const relTab = event.target.closest("[data-reltab]");
                if (relTab) { selectedRelTab = relTab.dataset.reltab; renderSidebar(); return; }

                const relMoreToggle = event.target.closest("[data-toggle-rel-more]");
                if (relMoreToggle) {
                  const dir = relMoreToggle.dataset.toggleRelMore;
                  relListExpanded[dir] = !relListExpanded[dir];
                  renderSidebar();
                  return;
                }

                const cyclesCell = event.target.closest("[data-open-cycles]");
                if (cyclesCell) { enterCyclesMode(null); return; }

                // Checado antes de data-toggle-pkg: mesmo elemento raiz
                // (checkbox), mas "marcar/desmarcar todos" no header.
                const allPkgCheckbox = event.target.closest("[data-toggle-all-pkg]");
                if (allPkgCheckbox) {
                  const allVisible = packagesList.every(p => !hiddenPackages.has(p.name));
                  if (allVisible) packagesList.forEach(p => hiddenPackages.add(p.name));
                  else packagesList.forEach(p => hiddenPackages.delete(p.name));
                  updateLeafVisibility();
                  renderSidebar();
                  return;
                }

                const pkgCheckbox = event.target.closest("[data-toggle-pkg]");
                if (pkgCheckbox) {
                  const pkg = pkgCheckbox.dataset.togglePkg;
                  if (hiddenPackages.has(pkg)) hiddenPackages.delete(pkg); else hiddenPackages.add(pkg);
                  updateLeafVisibility();
                  renderSidebar();
                  return;
                }

                const pkgSortHeader = event.target.closest("[data-pkg-sort]");
                if (pkgSortHeader) {
                  const col = pkgSortHeader.dataset.pkgSort;
                  if (pkgSortCol === col) pkgSortDir = pkgSortDir === "asc" ? "desc" : "asc";
                  else { pkgSortCol = col; pkgSortDir = "desc"; }
                  renderSidebar();
                  return;
                }

                const classSortHeader = event.target.closest("[data-class-sort]");
                if (classSortHeader) {
                  const col = classSortHeader.dataset.classSort;
                  if (classSortCol === col) classSortDir = classSortDir === "asc" ? "desc" : "asc";
                  else { classSortCol = col; classSortDir = "desc"; }
                  renderSidebar();
                  return;
                }

                const pkgRow = event.target.closest("[data-zoom-pkg]");
                if (pkgRow) {
                  const entry = packagesList.find(p => p.name === pkgRow.dataset.zoomPkg);
                  if (entry && entry.zoomTarget && focus !== entry.zoomTarget) zoom({ altKey: false }, entry.zoomTarget);
                  return;
                }

                const classRow = event.target.closest("[data-select-class]");
                if (classRow) { selectClass(classRow.dataset.selectClass); return; }

                const verLink = event.target.closest("[data-ver-cycle]");
                if (verLink) { enterCyclesMode(Number(verLink.dataset.verCycle)); return; }

                // Resto da linha do ciclo (fora do chevron) — narrows o mapa
                // pra esse ciclo específico; clicar de novo desfaz o narrow.
                const cycleRow = event.target.closest("[data-cycle-index]");
                if (cycleRow) {
                  const idx = Number(cycleRow.dataset.cycleIndex);
                  enterCyclesMode(cyclesModeFocusIndex === idx ? null : idx);
                  return;
                }

                const closeBtn = event.target.closest("[data-close]");
                if (closeBtn) { deselect(); return; }

                const relItem = event.target.closest("[data-qname]");
                if (relItem) { selectClass(relItem.dataset.qname); return; }

                const openBtn = event.target.closest("[data-open-editor]");
                if (openBtn) {
                  const payload = selectedQName;
                  __OPEN_FILE_QUERY_JS__
                  return;
                }

                const isolateBtn = event.target.closest("[data-isolate]");
                if (isolateBtn) { toggleIsolate(); return; }
              });
            }

            function renderSidebar() {
              sidebar.innerHTML = selectedQName ? classPanelHtml(selectedQName) : defaultPanelHtml();
            }

            function defaultPanelHtml() {
              const packages = packagesList.length;
              const relations = (data.edges || []).length;
              const cyclesCount = cyclesData.length;

              return `
                <div class="insp-summary">
                  <div class="insp-summary-cell">
                    <div class="insp-summary-number">${leaves.length}</div>
                    <div class="insp-summary-label">classes</div>
                  </div>
                  <div class="insp-summary-cell">
                    <div class="insp-summary-number">${packages}</div>
                    <div class="insp-summary-label">pacotes</div>
                  </div>
                  <div class="insp-summary-cell">
                    <div class="insp-summary-number">${relations}</div>
                    <div class="insp-summary-label">relações</div>
                  </div>
                  <div class="insp-summary-cell${cyclesCount > 0 ? " cycles" : ""}"${cyclesCount > 0 ? " data-open-cycles" : ""}>
                    <div class="insp-summary-number">${cyclesCount}</div>
                    <div class="insp-summary-label">ciclos${cyclesCount > 0 ? " ›" : ""}</div>
                  </div>
                </div>
                <div class="insp-tabs">
                  <span class="insp-tab${activeTab === "packages" ? " active" : ""}" data-tab="packages">Pacotes</span>
                  <span class="insp-tab${activeTab === "classes" ? " active" : ""}" data-tab="classes">Classes</span>
                  <span class="insp-tab${activeTab === "cycles" ? " active" : ""}" data-tab="cycles">Ciclos${cyclesCount > 0 ? ` <span class="insp-tab-count">${cyclesCount}</span>` : ""}</span>
                </div>
                ${activeTab === "packages" ? packagesTabHtml() : activeTab === "classes" ? classesTabHtml() : cyclesTabHtml()}
              `;
            }

            function packagesTabHtml() {
              if (packagesList.length === 0) return `<div class="insp-empty">Nenhum pacote.</div>`;

              const allVisible = packagesList.every(p => !hiddenPackages.has(p.name));
              const noneVisible = packagesList.every(p => hiddenPackages.has(p.name));
              const allState = allVisible ? " checked" : noneVisible ? "" : " indeterminate";

              const sorted = sortPackages(packagesList, pkgSortCol, pkgSortDir);
              const rows = sorted.map(p => {
                const checked = !hiddenPackages.has(p.name);
                return `
                  <div class="insp-row" data-zoom-pkg="${escapeHtml(p.name)}">
                    <span class="insp-checkbox${checked ? " checked" : ""}" data-toggle-pkg="${escapeHtml(p.name)}"></span>
                    <span class="insp-pkg-name" title="${escapeHtml(p.name)}">${escapeHtml(p.displayPkg)}</span>
                    <span class="insp-pkg-classes">${p.classCount}</span>
                  </div>
                `;
              }).join("");

              return `
                <div class="insp-table-header">
                  <span class="insp-col-check"><span class="insp-checkbox${allState}" data-toggle-all-pkg title="Marcar/desmarcar todos"></span></span>
                  <span class="insp-col-name insp-sortable" data-pkg-sort="name">Pacote <span class="insp-sort-arrow${pkgSortCol === "name" ? " active" : ""}">${sortIndicator("name", pkgSortCol, pkgSortDir)}</span></span>
                  <span class="insp-col-classes insp-sortable" data-pkg-sort="classes">Classes <span class="insp-sort-arrow${pkgSortCol === "classes" ? " active" : ""}">${sortIndicator("classes", pkgSortCol, pkgSortDir)}</span></span>
                </div>
                <div class="insp-scroll-area">${rows}</div>
                <div class="insp-footnote">Clique num pacote para dar zoom · desmarque para ocultar</div>
              `;
            }

            function classesTabHtml() {
              if (leaves.length === 0) return `<div class="insp-empty">Nenhuma classe.</div>`;
              const sorted = sortClasses(leaves, classSortCol, classSortDir);
              const rows = sorted.map(d => {
                const color = leafColor(d);
                return `
                  <div class="insp-class-row" data-select-class="${escapeHtml(d.data.qualifiedName)}">
                    <span class="insp-pkg-name" title="${escapeHtml(d.data.qualifiedName)}">${escapeHtml(d.data.name)}</span>
                    <span class="insp-class-pkg" title="${escapeHtml(packageOf(d))}">${escapeHtml(simplePkgOf(d))}</span>
                    <span class="insp-pkg-fanout"><span class="insp-fanout-dot" style="background:${color};"></span>${d.data.fanOut}</span>
                  </div>
                `;
              }).join("");
              return `
                <div class="insp-table-header">
                  <span class="insp-col-name insp-sortable" data-class-sort="name">Classe <span class="insp-sort-arrow${classSortCol === "name" ? " active" : ""}">${sortIndicator("name", classSortCol, classSortDir)}</span></span>
                  <span class="insp-col-classpkg insp-sortable" data-class-sort="package">Pacote <span class="insp-sort-arrow${classSortCol === "package" ? " active" : ""}">${sortIndicator("package", classSortCol, classSortDir)}</span></span>
                  <span class="insp-col-fanout insp-sortable" data-class-sort="fanout">Fan-out <span class="insp-sort-arrow${classSortCol === "fanout" ? " active" : ""}">${sortIndicator("fanout", classSortCol, classSortDir)}</span></span>
                </div>
                <div class="insp-scroll-area">${rows}</div>
              `;
            }

            function cyclesTabHtml() {
              if (cyclesData.length === 0) return `<div class="insp-empty">Nenhum ciclo encontrado.</div>`;
              const rows = cyclesData.map((cycle, index) => {
                const expanded = cyclesModeExpandedIndex === index;
                const path = cycle.map(qn => escapeHtml(lastSegment(qn))).join(" → ") + " → " + escapeHtml(lastSegment(cycle[0]));
                const row = `
                  <div class="insp-cycle-row${cyclesModeFocusIndex === index ? " active" : ""}" data-cycle-index="${index}">
                    <span class="insp-cycle-chevron${expanded ? " expanded" : ""}" data-toggle-cycle-expand="${index}">▶</span>
                    <span class="insp-cycle-number" style="background:${cycleColor(index)};">${index + 1}</span>
                    <span class="insp-cycle-path" title="${escapeHtml(path)}">${path}</span>
                  </div>
                `;
                const detail = expanded ? `<div class="insp-cycle-detail">${cycleDetailHtml(cycle)}</div>` : "";
                return row + detail;
              }).join("");
              return rows + `<div class="insp-footnote">Clique num ciclo para destacar as classes no mapa · clique na seta pra ver os tipos de relação</div>`;
            }

            const RELATION_PRIORITY = ["EXTENDS", "IMPLEMENTS", "USES", "INSTANTIATES", "CALLS"];

            // Agrupa arestas repetidas por classe destino/origem, já que uma
            // classe pode se relacionar com a mesma outra várias vezes no
            // código — mantém só o tipo de relação mais relevante (herança
            // antes de uso antes de chamada) e a contagem total.
            function dedupRelations(raw) {
              const map = new Map();
              const order = [];
              for (const item of raw) {
                let entry = map.get(item.qname);
                if (!entry) {
                  entry = { qname: item.qname, type: item.type, count: 0 };
                  map.set(item.qname, entry);
                  order.push(entry);
                }
                entry.count++;
                if (RELATION_PRIORITY.indexOf(item.type) < RELATION_PRIORITY.indexOf(entry.type)) {
                  entry.type = item.type;
                }
              }
              return order;
            }

            function relationListHtml(qName, direction) {
              if (relListExpandedQName !== qName) {
                relListExpandedQName = qName;
                relListExpanded = { uses: false, usedBy: false };
              }

              const raw = (data.edges || [])
                .filter(e => direction === "uses" ? e.source === qName : e.target === qName)
                .map(e => ({ qname: direction === "uses" ? e.target : e.source, type: e.type }));
              const items = dedupRelations(raw).sort((a, b) => b.count - a.count);
              const expanded = relListExpanded[direction];
              const visible = expanded ? items : items.slice(0, 8);
              const rest = items.length - visible.length;

              const rows = visible.map(item => `
                <div class="insp-rel-item">
                  <span class="insp-rel-name" data-qname="${escapeHtml(item.qname)}" title="${escapeHtml(item.qname)}">${escapeHtml(lastSegment(item.qname))}</span>
                  ${inSameCycle(qName, item.qname) ? '<span class="insp-rel-badge">CICLO</span>' : ""}
                  <span class="insp-rel-type">${item.type.toLowerCase()}${item.count > 1 ? ` ×${item.count}` : ""}</span>
                </div>
              `).join("") || `<div class="insp-empty">(nenhuma)</div>`;

              let more = "";
              if (rest > 0) {
                more = `<div class="insp-rel-more" data-toggle-rel-more="${direction}">+ ${rest} outras</div>`;
              } else if (expanded && items.length > 8) {
                more = `<div class="insp-rel-more" data-toggle-rel-more="${direction}">Mostrar menos</div>`;
              }
              return rows + more;
            }

            function comparisonRow(label, value, allValues, fillColor) {
              const sorted = [...allValues].sort((a, b) => a - b);
              const max = sorted[sorted.length - 1] || 1;
              const median = d3.median(sorted) || 0;
              const pct = max > 0 ? Math.min(100, (value / max) * 100) : 0;
              const medianPct = max > 0 ? Math.min(100, (median / max) * 100) : 0;
              return `
                <div class="compare-row">
                  <span class="compare-label">${label}</span>
                  <div class="compare-bar-bg">
                    <div class="compare-bar-fill" style="width:${pct}%; background:${fillColor};"></div>
                    <div class="compare-median" style="left:${medianPct}%;"></div>
                  </div>
                  <span class="compare-value">${value}</span>
                </div>
              `;
            }

            function classPanelHtml(qName) {
              const d = byQName.get(qName);
              if (!d) return defaultPanelHtml();
              const cls = d.data;
              const color = leafColor(d);
              const hasRing = fanInRatio(d) > 0;

              const cyclesForClass = cyclesData
                .map((cycle, index) => ({ cycle, index }))
                .filter(({ cycle }) => cycle.includes(qName));
              const cycleAlertHtml = cyclesForClass.length === 0 ? "" : (() => {
                const { cycle, index } = cyclesForClass[0];
                const otherQName = cycle.find(n => n !== qName) || cycle[0];
                return `
                  <div class="insp-cycle-alert${cyclesMode ? " emphasized" : ""}">
                    <span>⚠ Em ciclo com ${escapeHtml(lastSegment(otherQName))}</span>
                    <a data-ver-cycle="${index}">Ver ciclo</a>
                  </div>
                `;
              })();

              const cboValues = leaves.map(l => l.data.cbo);
              const locValues = leaves.map(l => l.data.loc);
              const ditValues = leaves.map(l => l.data.dit || 0);

              const usesCount   = (data.edges || []).filter(e => e.source === qName).length;
              const usedByCount = (data.edges || []).filter(e => e.target === qName).length;

              return `
                <div class="insp-class-header">
                  <div class="insp-class-dot${hasRing ? " ringed" : ""}" style="background:${color};"></div>
                  <div class="insp-class-header-text">
                    <div class="insp-class-title" title="${escapeHtml(cls.name)}">${escapeHtml(cls.name)}</div>
                    <div class="insp-class-qname" title="${escapeHtml(qName)}">${escapeHtml(qName)}</div>
                  </div>
                  <span class="insp-class-badge">${escapeHtml((TYPE_LABELS[cls.type] || cls.type).toUpperCase())}</span>
                  <button class="insp-close-btn" data-close title="Fechar">✕</button>
                </div>
                ${cycleAlertHtml}
                <div class="insp-metric-grid">
                  <div class="insp-metric-cell">
                    <div class="insp-metric-number">${cls.loc}</div>
                    <div class="insp-metric-label">linhas</div>
                  </div>
                  <div class="insp-metric-cell">
                    <div class="insp-metric-number fanout">${cls.fanOut}</div>
                    <div class="insp-metric-label">fan-out</div>
                  </div>
                  <div class="insp-metric-cell">
                    <div class="insp-metric-number fanin">${cls.fanIn}</div>
                    <div class="insp-metric-label">fan-in</div>
                  </div>
                </div>
                <div class="insp-compare">
                  <div class="insp-compare-title">Comparado ao projeto</div>
                  ${comparisonRow("CBO", cls.cbo, cboValues, "var(--compare-orange)")}
                  ${comparisonRow("LOC", cls.loc, locValues, "#3574f0")}
                  ${comparisonRow("DIT", cls.dit || 0, ditValues, "#3574f0")}
                  <div class="insp-compare-note">│ traço = mediana do projeto</div>
                </div>
                <div class="insp-rel-tabs">
                  <span class="insp-rel-tab${selectedRelTab === "uses" ? " active" : ""}" data-reltab="uses">
                    <span class="insp-rel-dot"></span> Usa ${usesCount}
                  </span>
                  <span class="insp-rel-tab${selectedRelTab === "usedBy" ? " active" : ""}" data-reltab="usedBy">
                    <span class="insp-rel-dot out"></span> Usada por ${usedByCount}
                  </span>
                </div>
                ${selectedRelTab === "uses" ? relationListHtml(qName, "uses") : relationListHtml(qName, "usedBy")}
                <div class="insp-actions">
                  <button class="insp-btn-primary" data-open-editor>Abrir no editor ↗</button>
                  ${cyclesMode ? "" : `<button class="insp-btn-secondary${isolateQName === qName ? " active" : ""}" data-isolate>Isolar no mapa</button>`}
                </div>
              `;
            }

            zoomTo([root.x, root.y, root.r * 2]);
            renderSidebar();
            wireSearch();
            wireSidebarEvents();
            updateBreadcrumb();

            // Botão "Limpar" do badge flutuante e "Sair" do badge de modo
            // ciclos — elementos estáticos (nunca recriados via innerHTML),
            // então ligam só uma vez aqui.
            document.querySelector("#selection-badge [data-clear-selection]")
              .addEventListener("click", deselect);
            document.querySelector("#cycles-mode-badge [data-exit-cycles-mode]")
              .addEventListener("click", exitCyclesMode);

            document.addEventListener("keydown", (event) => {
              if (event.key !== "Escape") return;
              if (cyclesMode) { exitCyclesMode(); return; }
              if (selectedQName) { deselect(); return; }
            });
          }
        })();
        </script>
        </body>
        </html>
        """;

    private static final String HTML_TEMPLATE = buildHtmlTemplate();

    private static String buildHtmlTemplate() {
        return HTML_TEMPLATE_HEAD + HTML_TEMPLATE_BODY;
    }
}
