package com.wiredoctor;

import java.io.File;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class WireDoctorHtmlReporter {
    private static final Logger log = LoggerFactory.getLogger(WireDoctorHtmlReporter.class);

    public static void generateHtmlReport(String jsonReport) {
        String html = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>WireDoctor Diagnostic Report</title>
            <script type="text/javascript" src="https://unpkg.com/vis-network/standalone/umd/vis-network.min.js"></script>
            <style>
                :root {
                    --bg-color: #0f172a;
                    --panel-bg: rgba(30, 41, 59, 0.85);
                    --text-main: #f8fafc;
                    --text-muted: #94a3b8;
                    --accent: #3b82f6;
                    --danger: #ef4444;
                    --warning: #f59e0b;
                    --proxy: #e76c87;
                    --orphan: #f97316;
                    --font: 'Inter', -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                }
                body {
                    margin: 0;
                    padding: 0;
                    background-color: var(--bg-color);
                    color: var(--text-main);
                    font-family: var(--font);
                    display: flex;
                    height: 100vh;
                    overflow: hidden;
                }
                #sidebar {
                    width: 400px;
                    background: var(--panel-bg);
                    backdrop-filter: blur(16px);
                    border-right: 1px solid rgba(255,255,255,0.1);
                    padding: 24px;
                    display: flex;
                    flex-direction: column;
                    gap: 24px;
                    overflow-y: auto;
                    z-index: 10;
                    box-shadow: 4px 0 25px rgba(0,0,0,0.5);
                }
                #graph-container {
                    flex-grow: 1;
                    position: relative;
                    background: radial-gradient(circle at center, #1e293b 0%, #0f172a 100%);
                }
                h1 { margin: 0; font-size: 28px; font-weight: 800; background: linear-gradient(135deg, #60a5fa, #a78bfa); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }
                h2 { margin: 0; font-size: 14px; text-transform: uppercase; letter-spacing: 1.5px; color: var(--text-muted); border-bottom: 1px solid rgba(255,255,255,0.1); padding-bottom: 8px;}
                .stat-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
                .stat-box { background: rgba(255,255,255,0.05); padding: 16px; border-radius: 12px; border: 1px solid rgba(255,255,255,0.05); transition: transform 0.2s ease;}
                .stat-box:hover { transform: translateY(-2px); background: rgba(255,255,255,0.08); }
                .stat-box .label { font-size: 12px; color: var(--text-muted); font-weight: 500; }
                .stat-box .value { font-size: 24px; font-weight: 700; margin-top: 6px; }
                .stat-box.danger .value { color: var(--danger); }
                .stat-box.proxy-stat .value { color: var(--proxy); }
                
                .step-list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 10px;}
                .step-item { background: rgba(255,255,255,0.03); padding: 12px; border-radius: 8px; display: flex; justify-content: space-between; align-items: center; border-left: 4px solid var(--accent); transition: background 0.2s ease;}
                .step-item:hover { background: rgba(255,255,255,0.06); }
                .step-name { font-size: 13px; word-break: break-all; }
                .step-time { font-size: 13px; font-weight: 700; color: var(--accent); white-space: nowrap; margin-left: 12px; background: rgba(59, 130, 246, 0.1); padding: 4px 8px; border-radius: 4px;}
                
                .legend { display: flex; flex-direction: column; gap: 10px; }
                .legend-item { display: flex; align-items: center; gap: 12px; font-size: 14px; font-weight: 500; color: #cbd5e1;}
                .legend-color { width: 14px; height: 14px; border-radius: 50%; border: 2px solid rgba(255,255,255,0.1); }
            </style>
        </head>
        <body>
            <div id="sidebar">
                <div>
                    <h1>WireDoctor</h1>
                    <div style="font-size: 13px; color: var(--text-muted); margin-top: 6px; font-weight: 500;">Runtime Application Analyzer</div>
                </div>
                
                <div>
                    <h2>Overview Architecture</h2>
                    <div class="stat-grid" style="margin-top: 16px;">
                        <div class="stat-box">
                            <div class="label">Total Beans</div>
                            <div class="value" id="stat-beans">-</div>
                        </div>
                        <div class="stat-box">
                            <div class="label">Total Wiring Edges</div>
                            <div class="value" id="stat-edges">-</div>
                        </div>
                        <div class="stat-box danger">
                            <div class="label">Cycles Detected</div>
                            <div class="value" id="stat-cycles">-</div>
                        </div>
                        <div class="stat-box proxy-stat">
                            <div class="label">Proxy Overhead</div>
                            <div class="value" id="stat-proxies">-</div>
                        </div>
                    </div>
                </div>

                <div>
                    <h2>Graph Legend</h2>
                    <div class="legend" style="margin-top: 16px;">
                        <div class="legend-item"><div class="legend-color" style="background: #10b981;"></div> Safe (Normal Bean)</div>
                        <div class="legend-item"><div class="legend-color" style="background: #ef4444; box-shadow: 0 0 10px #ef4444;"></div> Dangerous (Circular Dependency)</div>
                        <div class="legend-item"><div class="legend-color" style="background: #e76c87;"></div> Proxy (AOP/Tx/Async)</div>
                        <div class="legend-item"><div class="legend-color" style="background: #f97316;"></div> Orphan (Heuristic)</div>
                    </div>
                </div>

                <div style="flex-grow: 1; overflow-y: auto;">
                    <h2>Slowest Startup Steps (Top 10)</h2>
                    <ul class="step-list" id="slow-steps" style="margin-top: 16px;">
                        <!-- Injected by JS -->
                    </ul>
                </div>
            </div>
            
            <div id="graph-container"></div>

            <script>
                // DATA_INJECTION_POINT
                const reportData = /* DATA_INJECTION_POINT */;

                // 1. Populate Sidebar Stats
                document.getElementById('stat-beans').textContent = reportData.dependencies.totalBeans;
                document.getElementById('stat-edges').textContent = reportData.dependencies.totalEdges;
                document.getElementById('stat-cycles').textContent = reportData.dependencies.cyclesCount;
                
                const totalProxies = reportData.proxies.cglibCount + reportData.proxies.jdkCount;
                document.getElementById('stat-proxies').textContent = totalProxies;

                // 2. Populate Slowest Steps
                const stepsList = document.getElementById('slow-steps');
                if (reportData.startupSlowestSteps && reportData.startupSlowestSteps.length > 0) {
                    reportData.startupSlowestSteps.slice(0, 10).forEach(step => {
                        const li = document.createElement('li');
                        li.className = 'step-item';
                        li.innerHTML = `<span class="step-name">${step.name}</span><span class="step-time">${step.durationMs}ms</span>`;
                        stepsList.appendChild(li);
                    });
                } else {
                    stepsList.innerHTML = '<li class="step-item" style="border-left-color: #64748b; background: rgba(100,116,139,0.1);"><span class="step-name" style="color:#94a3b8">No startup timing data collected.</span></li>';
                }

                // 3. Prepare Graph Data
                const nodes = [];
                const edges = [];
                
                const cycleBeans = new Set();
                reportData.dependencies.cycles.forEach(cycle => cycle.forEach(b => cycleBeans.add(b)));
                
                const proxyBeans = new Set([...reportData.proxies.cglibBeans, ...reportData.proxies.jdkBeans]);
                const orphanBeans = new Set(reportData.dependencies.orphanBeans);

                const graphMap = reportData.dependencies.graph;
                const nodeSet = new Set();
                
                for (const [bean, deps] of Object.entries(graphMap)) {
                    nodeSet.add(bean);
                    deps.forEach(dep => {
                        nodeSet.add(dep);
                        edges.push({ from: bean, to: dep, arrows: 'to', color: { color: 'rgba(255,255,255,0.08)' } });
                    });
                }

                nodeSet.forEach(bean => {
                    let color = '#10b981'; // safe green
                    let shadow = false;
                    let size = 15;

                    if (cycleBeans.has(bean)) {
                        color = '#ef4444'; // dangerous red
                        shadow = { color: '#ef4444', size: 15 };
                        size = 25;
                    } else if (proxyBeans.has(bean)) {
                        color = '#e76c87'; // proxy pink
                        size = 20;
                    } else if (orphanBeans.has(bean)) {
                        color = '#f97316'; // orphan orange
                        size = 10;
                    }

                    nodes.push({
                        id: bean,
                        label: bean.length > 25 ? bean.substring(0, 22) + '...' : bean,
                        title: '<b>' + bean + '</b><br>' + (proxyBeans.has(bean) ? 'Status: Proxied<br>' : '') + (cycleBeans.has(bean) ? 'Status: In Circular Dependency<br>' : ''),
                        color: { background: color, border: 'rgba(0,0,0,0.3)', highlight: { background: '#34d399', border: '#fff'} },
                        shadow: shadow,
                        size: size,
                        shape: 'dot',
                        font: { color: '#e2e8f0', size: 12, face: 'Inter', strokeWidth: 2, strokeColor: '#0f172a' }
                    });
                });

                // Highlight Cycle Edges
                edges.forEach(edge => {
                    if (cycleBeans.has(edge.from) && cycleBeans.has(edge.to)) {
                        edge.color = { color: '#ef4444', highlight: '#f87171' };
                        edge.width = 3;
                    }
                });

                const container = document.getElementById('graph-container');
                const data = { nodes: new vis.DataSet(nodes), edges: new vis.DataSet(edges) };
                const options = {
                    physics: {
                        barnesHut: { gravitationalConstant: -3000, centralGravity: 0.3, springLength: 95 },
                        minVelocity: 0.75
                    },
                    interaction: { hover: true, tooltipDelay: 100 },
                    nodes: { borderWidth: 2 }
                };
                new vis.Network(container, data, options);
            </script>
        </body>
        </html>
        """.replace("/* DATA_INJECTION_POINT */", jsonReport);

        try {
            File htmlFile = new File("wiredoctor-report.html");
            Files.writeString(htmlFile.toPath(), html);
            log.info(WireDoctorMessages.SAVED_HTML_REPORT, htmlFile.getAbsolutePath());
        } catch (Exception e) {
            log.error(WireDoctorMessages.FAILED_WRITE_HTML, e.getMessage());
        }
    }
}
