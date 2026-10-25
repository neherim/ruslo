const AdminUi = (() => {
    const closest = (event, selector) =>
        event.target instanceof Element ? event.target.closest(selector) : null;

    const Theme = (() => {
        const STORAGE_KEY = 'admin-theme';

        function apply(theme) {
            const value = theme === 'dark' ? 'dark' : 'light';
            document.documentElement.setAttribute('data-theme', value);
        }

        function init() {
            try {
                apply(localStorage.getItem(STORAGE_KEY) || 'light');
            } catch (_) {
                apply('light');
            }
        }

        function toggle() {
            const next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
            apply(next);
            try {
                localStorage.setItem(STORAGE_KEY, next);
            } catch (_) {
                // Ignore storage restrictions.
            }
        }

        function handleClick(event) {
            if (!closest(event, '[data-theme-toggle]')) return false;
            toggle();
            return true;
        }

        return {init, apply, toggle, handleClick};
    })();

    const Sidebar = (() => {
        const STORAGE_KEY = 'admin-sidebar';

        function isCollapsed() {
            return document.documentElement.getAttribute('data-sidebar') === 'collapsed';
        }

        function syncToggle() {
            const collapsed = isCollapsed();
            const label = collapsed ? 'Expand sidebar' : 'Collapse sidebar';
            document.querySelectorAll('[data-sidebar-toggle]').forEach(button => {
                button.setAttribute('aria-expanded', String(!collapsed));
                button.setAttribute('aria-label', label);
                button.title = label;
                const text = button.querySelector('.nav-label');
                if (text) text.textContent = collapsed ? 'Expand' : 'Collapse';
            });
        }

        function toggle() {
            const collapsed = !isCollapsed();
            if (collapsed) {
                document.documentElement.setAttribute('data-sidebar', 'collapsed');
            } else {
                document.documentElement.removeAttribute('data-sidebar');
            }
            syncToggle();
            try {
                localStorage.setItem(STORAGE_KEY, collapsed ? 'collapsed' : 'expanded');
            } catch (_) {
                // Ignore storage restrictions.
            }
        }

        function handleClick(event) {
            if (!closest(event, '[data-sidebar-toggle]')) return false;
            toggle();
            return true;
        }

        return {init: syncToggle, handleClick};
    })();

    const Dialogs = (() => {
        function openDialog(id) {
            const dialog = document.getElementById(id);
            if (!dialog || typeof dialog.showModal !== 'function') return;
            if (!dialog.open) dialog.showModal();
        }

        function closeClosestDialog(source) {
            const dialog = source.closest('dialog');
            if (dialog) dialog.close();
        }

        function handleModalClick(event) {
            const modalOpen = closest(event, '[data-modal-open]');
            if (modalOpen) {
                openDialog(modalOpen.dataset.modalOpen);
                const dropdown = modalOpen.closest('details');
                if (dropdown) dropdown.open = false;
                return true;
            }

            const modalClose = closest(event, '[data-modal-close]');
            if (modalClose) {
                closeClosestDialog(modalClose);
                return true;
            }

            return false;
        }

        return {
            openDialog,
            closeClosestDialog,
            handleModalClick
        };
    })();

    // hx-confirm asks through the styled #confirm-dialog instead of the browser confirm().
    const Confirmations = (() => {
        let pending = null;

        function handleConfirm(event) {
            const dialog = document.getElementById('confirm-dialog');
            if (!event.detail.question || !dialog) return;

            event.preventDefault();
            pending = event.detail;
            document.getElementById('confirm-dialog-question').textContent = event.detail.question;
            Dialogs.openDialog('confirm-dialog');
        }

        function handleClick(event) {
            const accept = closest(event, '[data-confirm-accept]');
            if (!accept) return false;

            const request = pending;
            pending = null;
            Dialogs.closeClosestDialog(accept);
            if (request) request.issueRequest(true);
            return true;
        }

        return {handleConfirm, handleClick};
    })();

    const Toasts = (() => {
        function push(detail) {
            const stack = document.getElementById('toasts');
            if (!stack) return;

            const kind = (detail && detail.kind) || 'info';
            const text = (detail && detail.text) || '';
            const el = document.createElement('div');
            el.className = `toast toast-${kind}`;
            el.textContent = text;
            stack.appendChild(el);

            const remove = () => el.remove();
            el.addEventListener('click', remove);
            setTimeout(() => {
                el.classList.add('toast-leave');
                el.addEventListener('animationend', remove, {once: true});
            }, 3500);
        }

        return {push};
    })();

    const CopyButtons = (() => {
        // navigator.clipboard exists only in a secure context; an admin served over plain HTTP falls back to execCommand.
        function writeText(text) {
            if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text);

            const area = document.createElement('textarea');
            area.value = text;
            area.setAttribute('readonly', '');
            area.style.position = 'fixed';
            area.style.opacity = '0';
            document.body.appendChild(area);
            area.select();
            const copied = document.execCommand('copy');
            area.remove();
            return copied ? Promise.resolve() : Promise.reject(new Error('copy failed'));
        }

        function handleClick(event) {
            const button = closest(event, '[data-copy]');
            if (!button) return false;

            writeText(button.dataset.copy).then(
                () => Toasts.push({kind: 'success', text: 'Copied to clipboard'}),
                () => Toasts.push({kind: 'error', text: 'Could not copy to clipboard'}));
            return true;
        }

        return {handleClick};
    })();

    const ActionMenus = (() => {
        function handleClick(event) {
            if (closest(event, '.actions-menu')) return false;

            document.querySelectorAll('.actions-menu[open]').forEach(menu => {
                menu.open = false;
            });
            return false;
        }

        return {handleClick};
    })();

    // An empty value keeps the server default zone.
    function browserTimeZone() {
        try {
            return Intl.DateTimeFormat().resolvedOptions().timeZone || '';
        } catch (_) {
            return '';
        }
    }

    const ProcessList = (() => {
        function updateOffsetInput(value) {
            const input = document.getElementById('offset-input');
            if (input) input.value = String(Math.max(0, value));
        }

        function handlePaginationClick(event) {
            const pageBtn = closest(event, '[data-page-action]');
            if (!pageBtn) return false;

            const offset = parseInt(pageBtn.dataset.pageOffset || '0', 10);
            updateOffsetInput(offset);
            return true;
        }

        // The whole row opens the process; links, buttons and text selection keep their own behavior.
        function handleRowClick(event) {
            const row = closest(event, '.process-row');
            if (!row || closest(event, 'a, button, input, select, textarea')) return false;
            if (String(window.getSelection())) return false;

            const link = row.querySelector('a.process-id');
            if (!link) return false;
            if (event.ctrlKey || event.metaKey) {
                window.open(link.href, '_blank');
            } else {
                window.location.href = link.href;
            }
            return true;
        }

        function handlePageSizeChange(event) {
            const sizeSelect = closest(event, '[data-page-size]');
            if (!sizeSelect) return;

            const limitInput = document.getElementById('limit-input');
            if (limitInput) limitInput.value = sizeSelect.value;
            updateOffsetInput(0);
        }

        function handleTypeFilterChange(event) {
            const typeSelect = closest(event, '#type-filter');
            if (!typeSelect) return;

            const stateSelect = document.getElementById('state-filter');
            if (stateSelect) stateSelect.value = '';
            updateOffsetInput(0);
        }

        function handleFilterChange(event) {
            const changedFilter = closest(event, '#filters input, #filters select, #filters textarea');
            if (!changedFilter || changedFilter.id === 'limit-input' || changedFilter.id === 'offset-input') return;
            updateOffsetInput(0);
        }

        // datetime-local filters carry no zone, so the server reads them in the browser time zone.
        function initTimeZone() {
            const input = document.getElementById('time-zone-input');
            if (input) input.value = browserTimeZone();
        }

        return {
            initTimeZone,
            handlePaginationClick,
            handleRowClick,
            handlePageSizeChange,
            handleTypeFilterChange,
            handleFilterChange
        };
    })();

    const TimeFormatting = (() => {
        const localDateTimeFormat = typeof Intl !== 'undefined' && Intl.DateTimeFormat
            ? new Intl.DateTimeFormat(undefined, {
                year: 'numeric',
                month: '2-digit',
                day: '2-digit',
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit'
            })
            : null;

        function formatLocalDateTime(date) {
            return localDateTimeFormat ? localDateTimeFormat.format(date) : date.toISOString();
        }

        function applyLocalDateTimes(root) {
            const scope = root || document;
            scope.querySelectorAll('time.local-date-time').forEach(el => {
                const iso = el.getAttribute('datetime');
                if (!iso) return;
                const date = new Date(iso);
                if (Number.isNaN(date.getTime())) return;
                const text = formatLocalDateTime(date);
                el.textContent = text;
                el.title = text;
            });
        }

        function formatDuration(ms) {
            if (!Number.isFinite(ms) || ms < 0) return '—';
            const sec = Math.floor(ms / 1000);
            if (sec < 60) return `${sec}s`;
            const min = Math.floor(sec / 60);
            if (min < 60) return `${min}m ${sec % 60}s`;
            const h = Math.floor(min / 60);
            if (h < 24) return `${h}h ${min % 60}m`;
            const d = Math.floor(h / 24);
            return `${d}d ${h % 24}h`;
        }

        function applyDurations(root) {
            const scope = root || document;
            scope.querySelectorAll('[data-from]').forEach(el => {
                const from = el.dataset.from;
                const to = el.dataset.to;
                if (!from) return;
                const fromTime = new Date(from).getTime();
                const toTime = to ? new Date(to).getTime() : Date.now();
                if (!Number.isFinite(fromTime)) return;
                el.textContent = formatDuration(toTime - fromTime);
            });
        }

        // A due time that passed more than this long ago means the dispatcher has not picked the process up.
        const OVERDUE_AFTER_MS = 60 * 1000;

        function relativeText(ms, overdueCheck) {
            if (Math.abs(ms) < 5000) return 'now';
            if (ms > 0) return `in ${formatDuration(ms)}`;
            if (overdueCheck) return -ms > OVERDUE_AFTER_MS ? `overdue by ${formatDuration(-ms)}` : 'due now';
            return `${formatDuration(-ms)} ago`;
        }

        function applyRelativeTimes(root) {
            const scope = root || document;
            scope.querySelectorAll('time.relative-time').forEach(el => {
                const date = new Date(el.getAttribute('datetime'));
                if (Number.isNaN(date.getTime())) return;
                const ms = date.getTime() - Date.now();
                const overdueCheck = el.hasAttribute('data-overdue-check');
                el.textContent = relativeText(ms, overdueCheck);
                el.title = formatLocalDateTime(date);
                el.classList.toggle('overdue', overdueCheck && -ms > OVERDUE_AFTER_MS);
            });
        }

        function apply(root) {
            applyLocalDateTimes(root);
            applyRelativeTimes(root);
            applyDurations(root);
        }

        // Relative times and running durations age while the page stays open.
        function startTicking() {
            setInterval(() => {
                applyRelativeTimes(document);
                applyDurations(document);
            }, 10000);
        }

        return {apply, applyLocalDateTimes, formatLocalDateTime, startTicking};
    })();

    const VariableForms = (() => {
        function prettyJson(text) {
            try {
                return JSON.stringify(JSON.parse(text), null, 2);
            } catch (_) {
                return text;
            }
        }

        function formatValues(root) {
            (root || document).querySelectorAll('.variable-json').forEach(el => {
                el.textContent = prettyJson(el.textContent);
            });
        }

        function configureModal(title, submitText, name, value) {
            const dialog = document.getElementById('add-variable-modal');
            if (!dialog) return false;

            const titleEl = document.getElementById('variable-modal-title');
            const submitButton = document.getElementById('variable-submit-button');
            const statusLine = document.getElementById('add-variable-status');
            const nameInput = dialog.querySelector('input[name="name"]');
            const valueInput = dialog.querySelector('textarea[name="jsonValue"]');

            if (titleEl) titleEl.textContent = title;
            if (submitButton) submitButton.textContent = submitText;
            if (statusLine) {
                statusLine.textContent = '';
                statusLine.classList.remove('error');
            }
            if (nameInput) nameInput.value = name;
            if (valueInput) valueInput.value = value;

            Dialogs.openDialog('add-variable-modal');
            if (nameInput && !name) {
                nameInput.focus();
            } else if (valueInput) {
                valueInput.focus();
            }
            return true;
        }

        function handleClick(event) {
            if (closest(event, '[data-variable-add]')) {
                return configureModal('Add variable', 'Add', '', '{}');
            }

            const editButton = closest(event, '[data-variable-edit]');
            if (!editButton) return false;

            const row = editButton.closest('.variable-row');
            if (!row) return false;
            return configureModal(
                'Edit variable',
                'Save',
                row.dataset.variableName || '',
                prettyJson(row.dataset.variableValue || '{}'));
        }

        return {handleClick, formatValues};
    })();

    const TimerForms = (() => {
        // datetime-local takes local time without a zone; the form sends the browser zone with it.
        function toLocalInput(iso) {
            const date = iso ? new Date(iso) : new Date();
            if (Number.isNaN(date.getTime())) return '';
            const pad = value => String(value).padStart(2, '0');
            return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
                + `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
        }

        function handleClick(event) {
            const setButton = closest(event, '[data-timer-set]');
            if (!setButton) return false;

            const row = setButton.closest('.timer-row');
            const dialog = document.getElementById('set-timer-modal');
            if (!row || !dialog) return false;

            const statusLine = document.getElementById('set-timer-status');
            if (statusLine) {
                statusLine.textContent = '';
                statusLine.classList.remove('error');
            }
            dialog.querySelector('input[name="name"]').value = row.dataset.timerName || '';
            const momentInput = dialog.querySelector('input[name="moment"]');
            momentInput.value = toLocalInput(row.dataset.timerMoment);
            Dialogs.openDialog('set-timer-modal');
            momentInput.focus();
            return true;
        }

        return {handleClick};
    })();

    // Polling (process card, dashboard) skips a tick while the user is busy with the page,
    // so a redraw does not close a dialog, menu or full-screen diagram and does not drop a text selection.
    function canRefresh() {
        if (document.hidden || document.fullscreenElement) return false;
        if (document.querySelector('dialog[open], .actions-menu[open]')) return false;
        return !String(window.getSelection());
    }

    const ProcessDetails = (() => {
        let saved = null;

        function isDetailsSwap(event) {
            return event.detail.target && event.detail.target.id === 'process-details-content';
        }

        // A redraw keeps the selected tab and the expanded variables.
        function handleBeforeSwap(event) {
            if (!isDetailsSwap(event)) return;
            const tab = document.querySelector('input[name="data-tab"]:checked');
            saved = {
                tab: tab ? tab.id : null,
                openVariables: Array.from(document.querySelectorAll('.variable-row'))
                    .filter(row => row.querySelector('details.variable-value[open]'))
                    .map(row => row.dataset.variableName)
            };
        }

        function handleAfterSwap(event) {
            if (!isDetailsSwap(event) || !saved) return;
            event.detail.target.classList.add('refreshed');
            const tab = saved.tab && document.getElementById(saved.tab);
            if (tab) tab.checked = true;
            document.querySelectorAll('.variable-row').forEach(row => {
                const details = row.querySelector('details.variable-value');
                if (details && saved.openVariables.includes(row.dataset.variableName)) details.open = true;
            });
            saved = null;
        }

        return {handleBeforeSwap, handleAfterSwap};
    })();

    const StartProcess = (() => {
        function handleClick(event) {
            const startTrigger = closest(event, '[data-start-type]');
            if (!startTrigger) return false;

            Dialogs.openDialog('start-process-drawer');
            if (window.htmx) {
                window.htmx.ajax(
                    'GET',
                    `htmx/processes/start-form?type=${encodeURIComponent(startTrigger.dataset.startType)}`,
                    '#start-process-panel');
            }
            return true;
        }

        return {handleClick};
    })();

    const Diagram = (() => {
        const SCRIPT_URLS = [
            'assets/vendor/cytoscape/3.34.3/cytoscape.min.js',
            'assets/vendor/cytoscape-dagre/4.0.1/cytoscape-dagre.min.js'
        ];
        const START_NODE = '__start';
        const NODE_FONT_SIZE = 12;
        const NODE_TEXT_MAX_WIDTH = 180;
        const NODE_PADDING = 12;
        const EDGE_FONT_SIZE = 10;
        const MAX_FIT_ZOOM = 1.25;
        const LOOP_SWEEP_DEG = 50;
        // How far the loop control points stand out of the right side of the node.
        const LOOP_OUTSET = 40;
        const TRIGGER_ICONS = {MESSAGE: '✉', EVERY: '⏱', AFTER: '⏱', AT: '⏱'};
        const SYMBOL_FONT = '"Diagram Symbols"';
        const KIND_NAMES = {STEP: 'Step', AWAIT: 'Await', TERMINAL: 'Terminal'};
        const wheelBound = new WeakSet();
        let loading = null;
        let cy = null;
        // The instance data the current diagram was drawn with, to skip a rebuild when a card redraw did not change it.
        let drawnInstance = null;

        function status(message, error = false) {
            const target = document.getElementById('diagram-status');
            if (!target) return;
            target.textContent = message;
            target.classList.toggle('error', error);
        }

        function loadScript(src) {
            return new Promise((resolve, reject) => {
                const script = document.createElement('script');
                script.src = src;
                script.onload = () => resolve();
                script.onerror = () => reject(new Error(`Failed to load ${src}`));
                document.head.appendChild(script);
            });
        }

        function ensureLoaded() {
            if (window.cytoscape && window.cytoscapeDagre) return Promise.resolve();
            if (loading) return loading;

            // cytoscape-dagre registers itself in window.cytoscape, so the scripts load in order.
            loading = SCRIPT_URLS
                .reduce((chain, src) => chain.then(() => loadScript(src)), Promise.resolve())
                .catch(error => {
                    loading = null;
                    throw error;
                });
            return loading;
        }

        function cssVar(name, fallback) {
            const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
            return value || fallback;
        }

        function buildStyle() {
            const c = {
                font: cssVar('--font-sans', 'sans-serif'),
                surface: cssVar('--surface', '#ffffff'),
                surface2: cssVar('--surface-2', '#fafaf8'),
                ink: cssVar('--ink', '#1a1a17'),
                muted: cssVar('--ink-muted', '#6b6b66'),
                border: cssVar('--border-strong', '#d4d4cf'),
                accent: cssVar('--accent', '#3b3a8c'),
                accentSoft: cssVar('--accent-soft', '#eeeefa'),
                ok: cssVar('--ok', '#0f7c4a'),
                okSoft: cssVar('--ok-soft', '#def4e5'),
                danger: cssVar('--danger', '#a8201a'),
                dangerSoft: cssVar('--danger-soft', '#fadcd9')
            };
            return [
                {
                    selector: 'node',
                    style: {
                        'shape': 'round-rectangle',
                        'label': 'data(label)',
                        'width': 'data(width)',
                        'height': 'data(height)',
                        'background-color': c.surface2,
                        'border-width': 1.5,
                        'border-color': c.border,
                        'color': c.ink,
                        'font-family': c.font,
                        'font-size': NODE_FONT_SIZE,
                        'text-valign': 'center',
                        'text-halign': 'center',
                        'text-wrap': 'wrap',
                        'text-max-width': `${NODE_TEXT_MAX_WIDTH}px`
                    }
                },
                {selector: 'node.await', style: {'border-style': 'dashed'}},
                {selector: 'node.terminal', style: {'border-style': 'double', 'border-width': 4}},
                {
                    selector: `#${START_NODE}`,
                    style: {
                        'shape': 'ellipse',
                        'label': '',
                        'width': 14,
                        'height': 14,
                        'border-width': 0,
                        'background-color': c.ink
                    }
                },
                {
                    selector: 'edge',
                    style: {
                        'curve-style': 'bezier',
                        'width': 1.5,
                        'line-color': c.border,
                        'target-arrow-color': c.border,
                        'target-arrow-shape': 'triangle',
                        'arrow-scale': 0.9,
                        'control-point-step-size': 50
                    }
                },
                {
                    // A self-transition is drawn as a loop on the right side. Cytoscape puts the loop control points
                    // at 1.4 × step size from the node centre, so the step size follows the node width:
                    // a wide node would otherwise cover the loop.
                    selector: 'edge:loop',
                    style: {
                        'loop-direction': '90deg',
                        'loop-sweep': `-${LOOP_SWEEP_DEG}deg`,
                        'control-point-step-size': edge => (edge.source().data('width') / 2 + LOOP_OUTSET)
                            / (1.4 * Math.cos(LOOP_SWEEP_DEG / 2 * Math.PI / 180))
                    }
                },
                {
                    selector: 'edge[label]',
                    style: {
                        'label': 'data(label)',
                        'color': c.muted,
                        'font-family': `${SYMBOL_FONT}, ${c.font}`,
                        'font-size': EDGE_FONT_SIZE,
                        'text-background-color': c.surface,
                        'text-background-opacity': 1,
                        'text-background-padding': '2px'
                    }
                },
                {
                    // Non-loop edges follow the route computed by dagre around the nodes.
                    selector: 'edge:simple',
                    style: {
                        'curve-style': 'unbundled-bezier',
                        'control-point-weights': edge => edge.scratch('controlPointWeights') || [0.5],
                        'control-point-distances': edge => edge.scratch('controlPointDistances') || [0],
                        'edge-distances': 'intersection'
                    }
                },
                {
                    selector: 'edge[label]:loop',
                    style: {'text-margin-x': edge => textWidth(edge.data('label'), EDGE_FONT_SIZE) / 2 + 10}
                },
                {selector: 'edge.manual', style: {'line-style': 'dashed'}},
                {selector: 'node.visited', style: {'background-color': c.okSoft, 'border-color': c.ok}},
                {selector: 'edge.visited', style: {'width': 2.5, 'line-color': c.ok, 'target-arrow-color': c.ok}},
                {
                    selector: 'node.current',
                    style: {'background-color': c.accentSoft, 'border-color': c.accent, 'border-width': 3}
                },
                {selector: 'node.current.completed', style: {'background-color': c.okSoft, 'border-color': c.ok}},
                {selector: 'node.current.failed', style: {'background-color': c.dangerSoft, 'border-color': c.danger}}
            ];
        }

        // Cytoscape no longer sizes nodes by label, so the size is measured from the text.
        function textWidth(text, fontSize) {
            const context = document.createElement('canvas').getContext('2d');
            context.font = `${fontSize}px ${cssVar('--font-sans', 'sans-serif')}`;
            return context.measureText(text).width;
        }

        function nodeSize(label) {
            let width = 0;
            let lines = 0;
            label.split('\n').forEach(line => {
                const lineWidth = textWidth(line, NODE_FONT_SIZE);
                width = Math.max(width, lineWidth);
                lines += Math.max(1, Math.ceil(lineWidth / NODE_TEXT_MAX_WIDTH));
            });
            return {
                width: Math.min(width, NODE_TEXT_MAX_WIDTH) + NODE_PADDING * 2,
                height: lines * NODE_FONT_SIZE * 1.4 + NODE_PADDING * 2
            };
        }

        function splitList(value) {
            return (value || '').split(',').map(item => item.trim()).filter(Boolean);
        }

        // The process card keeps the instance data on the diagram section; the dashboard dialog draws no instance.
        function readInstance(canvas) {
            const section = canvas.closest('.diagram-section');
            const data = section ? section.dataset : {};
            return {
                history: splitList(data.history),
                historyAt: splitList(data.historyAt),
                current: data.current || '',
                status: (data.status || '').toLowerCase()
            };
        }

        function nodeTooltip(node, entries) {
            const lines = [];
            const kind = KIND_NAMES[node.kind] || node.kind;
            lines.push(node.worker && node.kind !== 'TERMINAL' ? `${kind} · worker ${node.worker}` : kind);
            if (entries.length > 0) {
                const last = new Date(entries[entries.length - 1]);
                const lastText = Number.isNaN(last.getTime()) ? '' : `, last ${TimeFormatting.formatLocalDateTime(last)}`;
                lines.push(`Entered ${entries.length} ${entries.length === 1 ? 'time' : 'times'}${lastText}`);
            }
            return lines.join('\n');
        }

        function edgeLabel(edge) {
            if (!edge.label) return null;
            const icon = TRIGGER_ICONS[edge.trigger];
            return icon ? `${icon} ${edge.label}` : edge.label;
        }

        function pairKey(source, target) {
            return `${source}\u0000${target}`;
        }

        function buildElements(graph, instance) {
            const known = new Set(graph.nodes.map(node => node.id));
            const visited = new Set(instance.history);
            const visitedPairs = new Set();
            for (let i = 1; i < instance.history.length; i++) {
                const source = instance.history[i - 1];
                const target = instance.history[i];
                if (known.has(source) && known.has(target)) visitedPairs.add(pairKey(source, target));
            }

            const entries = new Map();
            instance.history.forEach((state, i) => {
                if (!entries.has(state)) entries.set(state, []);
                entries.get(state).push(instance.historyAt[i]);
            });

            const elements = [{group: 'nodes', data: {id: START_NODE}}];
            graph.nodes.forEach(node => {
                const classes = [node.kind.toLowerCase()];
                if (visited.has(node.id)) classes.push('visited');
                if (node.id === instance.current) classes.push('current', instance.status);
                const nodeEntries = entries.get(node.id) || [];
                // The state code is what the card, filters and the move dialog show, so it is drawn under the description.
                let label = node.label === node.id ? node.id : `${node.label}\n${node.id}`;
                if (nodeEntries.length > 1) label += `\n×${nodeEntries.length}`;
                elements.push({
                    group: 'nodes',
                    data: {id: node.id, label, tooltip: nodeTooltip(node, nodeEntries), ...nodeSize(label)},
                    classes: classes.join(' ')
                });
            });

            elements.push({
                group: 'edges',
                data: {id: 'start-edge', source: START_NODE, target: graph.initialState},
                classes: instance.history.length > 0 ? 'visited' : ''
            });
            const declaredPairs = new Set();
            graph.edges.forEach((edge, index) => {
                const key = pairKey(edge.source, edge.target);
                declaredPairs.add(key);
                const data = {id: `edge-${index}`, source: edge.source, target: edge.target};
                const label = edgeLabel(edge);
                if (label) data.label = label;
                const classes = [edge.trigger.toLowerCase()];
                if (visitedPairs.has(key)) classes.push('visited');
                elements.push({group: 'edges', data, classes: classes.join(' ')});
            });
            // Transitions outside the definition (administrative move) are drawn as extra dashed edges.
            Array.from(visitedPairs)
                .filter(key => !declaredPairs.has(key))
                .forEach((key, index) => {
                    const [source, target] = key.split('\u0000');
                    elements.push({
                        group: 'edges',
                        data: {id: `manual-${index}`, source, target},
                        classes: 'manual visited'
                    });
                });
            return elements;
        }

        async function fetchGraph(type) {
            const response = await fetch(`api/definitions/${encodeURIComponent(type)}/graph`, {
                headers: {'Accept': 'application/json'}
            });
            if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
            return response.json();
        }

        function destroy() {
            if (!cy) return;
            try {
                cy.destroy();
            } catch (_) {
                // Ignore stale instance cleanup failures.
            }
            cy = null;
            drawnInstance = null;
        }

        // A small diagram is not stretched over the canvas: its text stays close to the page text size.
        function fit() {
            if (!cy) return;
            cy.resize();
            cy.fit(undefined, 30);
            if (cy.zoom() > MAX_FIT_ZOOM) {
                cy.zoom(MAX_FIT_ZOOM);
                cy.center();
            }
        }

        function stepZoom(factor, renderedPosition) {
            if (!cy) return;
            const container = cy.container();
            cy.zoom({
                level: cy.zoom() * factor,
                renderedPosition: renderedPosition || {x: container.clientWidth / 2, y: container.clientHeight / 2}
            });
        }

        // Plain wheel scrolls the page; Ctrl/Cmd + wheel (and trackpad pinch) zooms the diagram.
        function bindWheelZoom(canvas) {
            if (wheelBound.has(canvas)) return;
            wheelBound.add(canvas);
            canvas.addEventListener('wheel', event => {
                if (!cy || !(event.ctrlKey || event.metaKey)) return;
                event.preventDefault();
                const rect = canvas.getBoundingClientRect();
                stepZoom(Math.pow(1.002, -event.deltaY), {x: event.clientX - rect.left, y: event.clientY - rect.top});
            }, {passive: false});
        }

        function toggleFullscreen(control) {
            const shell = control.closest('.diagram-canvas-shell');
            if (!shell || !shell.requestFullscreen) return;
            if (document.fullscreenElement) {
                document.exitFullscreen();
            } else {
                shell.requestFullscreen().catch(() => status('Full screen is not available', true));
            }
        }

        function handleFullscreenChange() {
            requestAnimationFrame(fit);
        }


        // A given viewport (zoom and pan) is restored instead of fitting the diagram to the canvas.
        async function render(viewport) {
            const canvas = document.getElementById('diagram-canvas');
            // The dashboard dialog canvas gets its type only when the dialog opens.
            if (!canvas || !canvas.dataset.diagramType || canvas.offsetParent === null) return;
            const type = canvas.dataset.diagramType;

            try {
                await ensureLoaded();
            } catch (_) {
                status('Could not load diagram library', true);
                return;
            }
            // The canvas does not redraw when a font arrives later, so the symbol font is loaded up front.
            await document.fonts.load(`${EDGE_FONT_SIZE}px ${SYMBOL_FONT}`, '✉').catch(() => {});

            try {
                const graph = await fetchGraph(type);
                const instance = readInstance(canvas);
                destroy();
                cy = window.cytoscape({
                    container: canvas,
                    elements: buildElements(graph, instance),
                    style: buildStyle(),
                    minZoom: 0.2,
                    maxZoom: 3,
                    userZoomingEnabled: false,
                    autoungrabify: true,
                    boxSelectionEnabled: false
                });
                cy.layout({
                    name: 'dagre',
                    rankDir: 'TB',
                    nodeSep: 50,
                    rankSep: 28,
                    edgeSep: 24,
                    fit: false,
                    // A labelled edge spans two ranks so its label gets an empty rank in the middle.
                    minLen: edge => edge.data('label') ? 2 : 1,
                    useDagreEdgeControlPoints: true
                }).run();
                if (viewport) {
                    cy.viewport(viewport);
                } else {
                    fit();
                }
                drawnInstance = JSON.stringify(instance);
                bindWheelZoom(canvas);
                cy.on('mouseover', 'node', event => {
                    if (event.target.id() !== START_NODE) canvas.title = event.target.data('tooltip');
                });
                cy.on('mouseout', 'node', () => {
                    canvas.title = '';
                });
                status('');
            } catch (_) {
                status('No process diagram available', true);
            }
        }

        function handleControlClick(event) {
            const control = closest(event, '[data-diagram-control]');
            if (!control) return false;

            const action = control.dataset.diagramControl;
            if (action === 'zoom-in') {
                stepZoom(1.2);
            } else if (action === 'zoom-out') {
                stepZoom(1 / 1.2);
            } else if (action === 'fit') {
                fit();
            } else if (action === 'fullscreen') {
                toggleFullscreen(control);
            }
            return true;
        }

        function handleDialogClick(event) {
            const trigger = closest(event, '[data-diagram-open]');
            if (!trigger) return false;

            const type = trigger.dataset.diagramType;
            const canvas = document.getElementById('diagram-canvas');
            const titleEl = document.getElementById('diagram-dialog-title');
            if (canvas) canvas.dataset.diagramType = type;
            if (titleEl) titleEl.textContent = `Diagram — ${type}`;
            // The diagram of the previously opened type is not shown while the new one loads.
            destroy();
            status('Loading');
            Dialogs.openDialog('diagram-dialog');
            requestAnimationFrame(() => render());
            return true;
        }

        // The canvas survives a process card redraw (hx-preserve). The diagram is rebuilt only when the instance
        // changed, keeps the zoom and position the user chose, and stays on screen until the new one is ready.
        function handleAfterSwap(event) {
            const canvas = event.target.querySelector && event.target.querySelector('#diagram-canvas');
            if (!canvas) return;
            if (cy && drawnInstance === JSON.stringify(readInstance(canvas))) return;
            render(cy ? {zoom: cy.zoom(), pan: cy.pan()} : null);
        }

        function watchTheme() {
            new MutationObserver(() => {
                if (cy) cy.style(buildStyle());
            }).observe(document.documentElement, {attributes: true, attributeFilter: ['data-theme']});
        }

        return {
            render,
            watchTheme,
            handleFullscreenChange,
            handleControlClick,
            handleDialogClick,
            handleAfterSwap
        };
    })();

    const clickHandlers = [
        ActionMenus.handleClick,
        Theme.handleClick,
        Sidebar.handleClick,
        CopyButtons.handleClick,
        Confirmations.handleClick,
        VariableForms.handleClick,
        TimerForms.handleClick,
        Diagram.handleControlClick,
        StartProcess.handleClick,
        Diagram.handleDialogClick,
        Dialogs.handleModalClick,
        ProcessList.handlePaginationClick,
        ProcessList.handleRowClick
    ];

    function handleBodyClick(event) {
        clickHandlers.some(handler => handler(event));
    }

    function handleHtmxAfterRequest(event) {
        const el = event.detail && event.detail.elt;
        if (!el) return;

        const detail = event.detail;
        const success = detail.successful !== false
            && detail.xhr && detail.xhr.status >= 200 && detail.xhr.status < 300;
        if (!success) return;

        const modal = el.closest('dialog.small-modal');
        if (modal && el.tagName === 'FORM') {
            modal.close();
        }
    }

    function handleHtmxAfterSwap(event) {
        ProcessDetails.handleAfterSwap(event);
        Diagram.handleAfterSwap(event);
        TimeFormatting.apply(event.target);
        VariableForms.formatValues(event.target);
    }

    function init() {
        Theme.init();
        Sidebar.init();
        ProcessList.initTimeZone();
        TimeFormatting.apply(document);
        TimeFormatting.startTicking();
        VariableForms.formatValues(document);
        Diagram.watchTheme();
        Diagram.render();
    }

    // A failed admin action answers 422 with an error status line: swap it into the target, keep the dialog open.
    if (window.htmx) {
        window.htmx.config.responseHandling = [
            {code: '204', swap: false},
            {code: '[23]..', swap: true},
            {code: '422', swap: true},
            {code: '[45]..', swap: false, error: true}
        ];
    }

    document.body.addEventListener('click', handleBodyClick);
    document.body.addEventListener('toast', event => Toasts.push(event.detail));
    document.body.addEventListener('change', ProcessList.handleTypeFilterChange, true);
    document.body.addEventListener('change', ProcessList.handleFilterChange, true);
    document.body.addEventListener('input', ProcessList.handleFilterChange, true);
    document.body.addEventListener('change', ProcessList.handlePageSizeChange, true);
    document.body.addEventListener('htmx:confirm', Confirmations.handleConfirm);
    document.body.addEventListener('htmx:beforeSwap', ProcessDetails.handleBeforeSwap);
    document.body.addEventListener('htmx:afterSwap', handleHtmxAfterSwap);
    document.body.addEventListener('htmx:afterRequest', handleHtmxAfterRequest);
    document.addEventListener('fullscreenchange', Diagram.handleFullscreenChange);

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    return {
        renderDiagram: Diagram.render,
        openDialog: Dialogs.openDialog,
        openModal: Dialogs.openDialog,
        pushToast: Toasts.push,
        applyTheme: Theme.apply,
        toggleTheme: Theme.toggle,
        applyLocalDateTimes: TimeFormatting.applyLocalDateTimes,
        canRefresh,
        browserTimeZone
    };
})();

// htmx trigger filters resolve names through window, where a top-level const is not visible.
window.AdminUi = AdminUi;
